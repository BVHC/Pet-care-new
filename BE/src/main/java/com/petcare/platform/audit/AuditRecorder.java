package com.petcare.platform.audit;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.hibernate.proxy.HibernateProxy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.platform.config.TraceContext;

import jakarta.persistence.Entity;
import lombok.extern.slf4j.Slf4j;

/**
 * Điểm duy nhất ghi {@code audit_logs} (BR-QT-15, 16). Quyết định thiết kế: docs/adr/0001-audit-recording.md.
 *
 * <ul>
 *   <li>{@link #record} — mặc định. Bắt buộc chạy trong transaction của use case ({@code MANDATORY}): nghiệp vụ
 *       rollback thì audit cũng mất, ghi audit lỗi thì nghiệp vụ rollback (erd L802).</li>
 *   <li>{@link #recordIndependently} — chỉ cho sự kiện <b>thất bại</b> mà nghiệp vụ sẽ rollback nhưng audit phải còn
 *       ({@code LOGIN_FAILED}, từ chối 403 theo BR-QT-01). Transaction riêng ({@code REQUIRES_NEW}); nếu chính lệnh
 *       ghi lỗi thì log ERROR và nuốt lỗi để client vẫn nhận lỗi gốc.</li>
 * </ul>
 *
 * Entry sai (action/entityType sai mẫu, snapshot là entity hoặc chứa khóa nhạy cảm, actor thiếu email) là lỗi lập
 * trình: ném {@link IllegalArgumentException}/{@link IllegalStateException} ở cả hai hàm, trước khi chạm DB.
 */
@Slf4j
@Component
public class AuditRecorder {

    /** {@code audit_logs.action VARCHAR(50)}, ví dụ {@code LOGIN_FAILED} (erd L155). */
    static final Pattern ACTION_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{2,49}$");

    /** {@code audit_logs.entity_type VARCHAR(50)} là tên bảng (erd L156). */
    static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,49}$");

    /** Từ trong tên khóa của snapshot bị cấm: mật khẩu, OTP, token, bí mật, CCCD (BR-TK-16 không lưu CCCD). */
    static final Set<String> SENSITIVE_WORDS = Set.of("password", "passwd", "otp", "token", "secret", "cccd", "pin");

    /** Tách {@code passwordHash}, {@code token_hash}, {@code OTPCode} thành từng từ. */
    private static final Pattern KEY_WORD_SPLIT =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^A-Za-z0-9]+");

    static final int EMAIL_MAX_LENGTH = 255;
    static final int IP_MAX_LENGTH = 45;

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AuditRecorder(AuditLogRepository repository, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        repository.save(toEntity(entry));
    }

    public void recordIndependently(AuditEntry entry) {
        AuditLogEntity auditLog = toEntity(entry);
        try {
            requiresNew.executeWithoutResult(status -> repository.save(auditLog));
        } catch (RuntimeException ex) {
            log.error("AUDIT_WRITE_FAILED action={} entityType={} entityId={} actorAccountId={} actorEmail={} "
                            + "ipAddress={} traceId={} reason={} before={} after={}",
                    auditLog.getAction(), auditLog.getEntityType(), auditLog.getEntityId(),
                    auditLog.getActorAccountId(), auditLog.getActorEmail(), auditLog.getIpAddress(),
                    TraceContext.current(), auditLog.getReason(), auditLog.getBeforeData(), auditLog.getAfterData(),
                    ex);
        }
    }

    private AuditLogEntity toEntity(AuditEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Audit entry is required");
        }
        if (entry.action() == null || !ACTION_PATTERN.matcher(entry.action()).matches()) {
            throw new IllegalArgumentException("Invalid audit action: " + entry.action());
        }
        if (entry.entityType() != null && !ENTITY_TYPE_PATTERN.matcher(entry.entityType()).matches()) {
            throw new IllegalArgumentException("Invalid audit entityType: " + entry.entityType());
        }
        if (entry.entityId() != null && entry.entityType() == null) {
            throw new IllegalArgumentException("Audit entityId requires entityType");
        }

        Long actorAccountId;
        String actorEmail;
        if (entry.actorOverridden()) {
            actorAccountId = entry.actorAccountId();
            actorEmail = entry.actorEmail();
        } else {
            AuditPrincipal principal = currentPrincipal();
            actorAccountId = principal == null ? null : principal.accountId();
            actorEmail = principal == null ? null : principal.email();
        }
        if (actorAccountId != null && (actorEmail == null || actorEmail.isBlank())) {
            throw new IllegalArgumentException("Audit actor with accountId must have an email");
        }

        return new AuditLogEntity(actorAccountId, truncate(actorEmail, EMAIL_MAX_LENGTH), entry.action(),
                entry.entityType(), entry.entityId(), snapshot("before", entry.before()),
                snapshot("after", entry.after()), entry.reason(), truncate(currentIpAddress(), IP_MAX_LENGTH));
    }

    /** {@code null} = hệ thống (job, chưa đăng nhập): cả hai cột actor để NULL. */
    private static AuditPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AuditPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("Authenticated principal must implement AuditPrincipal: "
                + authentication.getPrincipal().getClass().getName());
    }

    /** Sau {@code server.forward-headers-strategy=native}, remoteAddr đã là IP client (docs/adr/0002). */
    private static String currentIpAddress() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    private JsonNode snapshot(String name, Object value) {
        if (value == null) {
            return null;
        }
        rejectEntity(name, value);
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> rejectEntity(name, element));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(element -> rejectEntity(name, element));
        }
        JsonNode tree = objectMapper.valueToTree(value);
        rejectSensitiveKeys(name, tree);
        return tree;
    }

    private static void rejectEntity(String name, Object value) {
        if (value != null && (value instanceof HibernateProxy || value.getClass().isAnnotationPresent(Entity.class))) {
            throw new IllegalArgumentException(
                    "Audit " + name + " must be a snapshot, not a JPA entity: " + value.getClass().getName());
        }
    }

    private static void rejectSensitiveKeys(String name, JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (isSensitiveKey(field.getKey())) {
                    throw new IllegalArgumentException(
                            "Audit " + name + " contains sensitive key: " + field.getKey());
                }
                rejectSensitiveKeys(name, field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(element -> rejectSensitiveKeys(name, element));
        }
    }

    static boolean isSensitiveKey(String key) {
        for (String word : KEY_WORD_SPLIT.split(key)) {
            if (SENSITIVE_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
