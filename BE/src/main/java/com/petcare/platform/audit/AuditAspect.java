package com.petcare.platform.audit;

import com.petcare.platform.outbox.OutboxJsonSupport;
import com.petcare.platform.security.TraceIdFilter;
import com.petcare.platform.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * docs/convention/backend/08-logging-and-audit.md §8.2 — thực thi audit cho mọi
 * method đánh dấu {@link Auditable}: (1) log SLF4J có cấu trúc cho ops/monitoring
 * real-time, (2) ghi 1 row {@code audit_logs} (docs/06-erd.md, RULE-25-08 — lưu
 * trữ bất biến tối thiểu 5 năm) làm audit trail bền vững — trước đây chỉ có (1),
 * không thoả RULE-02-07 ("lý do giải trình bắt buộc ghi nhận vào Audit Log": SLF4J
 * log không ghi tham số method để tránh lộ dữ liệu nhạy cảm như mật khẩu tạm trong
 * CreateStaffRequest, nên vô tình bỏ luôn field {@code reason} — field duy nhất
 * RULE-02-07 yêu cầu).
 * <p>
 * Chỉ field được đánh dấu tường minh ({@link AuditResourceId}/{@link AuditDetail}/
 * {@link AuditOrganizationId}/{@link AuditStoreId}) mới lọt vào {@code audit_logs} —
 * không tự động dump toàn bộ tham số thô. Riêng {@code organization_id}/{@code store_id}
 * lấy theo thứ tự ưu tiên (dừng ở nguồn đầu tiên có giá trị):
 * <ol>
 *   <li>Tham số đánh dấu {@link AuditOrganizationId}/{@link AuditStoreId} (biết TRƯỚC
 *   khi {@code proceed()} — dùng cho method không có accessor phù hợp trên kiểu trả về,
 *   vd {@code AccountSummary} của Lock/Unlock/Deactivate/Reactivate).</li>
 *   <li>Accessor kiểu record {@code organizationId()}/{@code storeId()} trên giá trị
 *   TRẢ VỀ (dò SAU khi {@code proceed()} xong, cùng kỹ thuật reflection opt-in như
 *   {@code previousStatus()}/{@link #fallbackResourceIdFromResult} — vd
 *   {@code RoleAssignmentResponse}/{@code OrganizationResponse} đã có sẵn 2 field này,
 *   không cần sửa method nghiệp vụ nào).</li>
 *   <li>Scope của ACTOR (hành vi gốc, mặc định khi 2 nguồn trên đều không có).</li>
 * </ol>
 * Không có nguồn nào ở trên → giữ nguyên hành vi cũ dùng scope actor, xem javadoc
 * {@link AuditOrganizationId}.
 * <p>
 * {@code snapshot_before}/{@code snapshot_after} (RULE-25-01 `previous_state`/`new_state`):
 * {@code snapshot_after} build từ tham số {@link AuditDetail}; {@code snapshot_before} dò
 * accessor {@code previousStatus()} trên giá trị trả về (opt-in theo kiểu dữ liệu, không
 * bắt buộc mọi method @Auditable phải có).
 * <p>
 * {@code client_ip}/{@code user_agent} (RULE-25-01 `ip_address`/`client_channel`): lấy từ
 * {@link HttpServletRequest} của thread hiện tại qua {@link RequestContextHolder} — NULL nếu
 * action chạy ngoài 1 HTTP request (tác vụ nền, test).
 * <p>
 * Ghi audit_logs KHÔNG được làm rớt transaction nghiệp vụ chính nếu thất bại
 * (constraint lạ, DB hiccup...) — bọc try/catch, log ERROR rồi bỏ qua, cùng triết
 * lý fail-open đã áp dụng cho {@code TokenBlacklistService} (Redis sập không được
 * kéo sập API nghiệp vụ).
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    private final AuditLogRepository auditLogRepository;

    public AuditAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        UUID actorAccountId = null;
        UUID actorOrganizationId = null;
        UUID actorStoreId = null;
        String actorId = "anonymous";
        String actorRole = "none";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            actorId = String.valueOf(principal.getUserId());
            actorRole = principal.getRole() != null ? principal.getRole().name() : "none";
            actorAccountId = principal.getAccountId();
            actorOrganizationId = principal.getOrganizationId();
            actorStoreId = principal.getStoreId();
        }

        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        String action = auditable.action();

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();
        String resourceId = extractResourceId(parameters, args);
        // RULE-25-01 (`scope_id`) — org/store của RESOURCE BỊ TÁC ĐỘNG, không phải của actor.
        // SUPER_ADMIN luôn có organizationId=null (RoleScopeGuard.validateRoleScopeBinding) nên
        // nếu không override, mọi hành động Platform Admin thực hiện trên 1 Organization cụ thể
        // sẽ ghi audit_logs.organization_id=NULL — Organization Admin tra cứu theo scope
        // (RULE-25-07) sẽ không thấy được. Chỉ trích xuất được từ tham số ở đây (biết TRƯỚC
        // proceed()) — phần dò accessor trên KẾT QUẢ trả về nằm trong persist() (cần result).
        UUID paramOrganizationId = extractMarkedUuid(parameters, args, AuditOrganizationId.class);
        UUID paramStoreId = extractMarkedUuid(parameters, args, AuditStoreId.class);

        try {
            Object result = joinPoint.proceed();
            log.info("action={} actorId={} actorRole={} outcome=SUCCESS traceId={} timestamp={}",
                    action, actorId, actorRole, traceId, Instant.now());
            persist(auditable, actorAccountId, actorOrganizationId, actorStoreId, paramOrganizationId, paramStoreId,
                    resourceId != null ? resourceId : fallbackResourceIdFromResult(result),
                    result, parameters, args);
            return result;
        } catch (Throwable ex) {
            log.warn("action={} actorId={} actorRole={} outcome=FAILURE reason={} traceId={} timestamp={}",
                    action, actorId, actorRole, ex.getClass().getSimpleName(), traceId, Instant.now());
            throw ex;
        }
    }

    private void persist(Auditable auditable, UUID actorAccountId, UUID actorOrganizationId, UUID actorStoreId,
                          UUID paramOrganizationId, UUID paramStoreId,
                          String resourceId, Object result, Parameter[] parameters, Object[] args) {
        try {
            // Thứ tự ưu tiên org/store: xem javadoc lớp — (1) tham số đánh dấu, (2) accessor
            // trên result, (3) scope actor. Đặt TRONG try/catch này (không phải ở audit()) để
            // reflection probe (2) — dù bản thân đã tự bắt exception nội bộ — không có đường
            // nào lọt ra ngoài ảnh hưởng tới business call, giữ đúng triết lý fail-open.
            UUID organizationId = firstNonNull(paramOrganizationId,
                    resolveUuidAccessor(result, "organizationId"), actorOrganizationId);
            UUID storeId = firstNonNull(paramStoreId, resolveUuidAccessor(result, "storeId"), actorStoreId);

            AuditLog entity = new AuditLog();
            entity.setAccountId(actorAccountId);
            entity.setOrganizationId(organizationId);
            entity.setStoreId(storeId);
            entity.setAction(auditable.action());
            entity.setResourceType(auditable.resourceType());
            entity.setResourceId(resourceId != null ? resourceId : "N/A");
            entity.setSnapshotBefore(buildPreviousStateJson(result));
            entity.setSnapshotAfter(buildDetailJson(parameters, args));
            populateRequestContext(entity);
            auditLogRepository.save(entity);
        } catch (Exception ex) {
            log.error("Failed to persist audit_logs row for action={} — audit trail incomplete, business action NOT rolled back",
                    auditable.action(), ex);
        }
    }

    /** Tham số đánh dấu {@link AuditResourceId} (nếu có) — ưu tiên trước, biết được TRƯỚC khi proceed(). */
    private String extractResourceId(Parameter[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(AuditResourceId.class) && args[i] != null) {
                return String.valueOf(args[i]);
            }
        }
        return null;
    }

    /** Tham số kiểu {@link UUID} đánh dấu {@code annotationType} (vd {@link AuditOrganizationId}). */
    private UUID extractMarkedUuid(Parameter[] parameters, Object[] args, Class<? extends Annotation> annotationType) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(annotationType) && args[i] instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }

    private static UUID firstNonNull(UUID... values) {
        for (UUID value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Dò accessor kiểu record {@code accessorName()} (vd {@code organizationId}/{@code storeId})
     * trên giá trị trả về, chỉ nhận nếu kiểu trả về đúng {@link UUID} — cùng kỹ thuật reflection
     * opt-in như {@link #fallbackResourceIdFromResult}/{@link #buildPreviousStateJson}. Method
     * @Auditable nào trả về kiểu không có accessor này thì trả {@code null}, không ảnh hưởng.
     */
    private UUID resolveUuidAccessor(Object result, String accessorName) {
        if (result == null) {
            return null;
        }
        try {
            Method getter = result.getClass().getMethod(accessorName);
            Object value = getter.invoke(result);
            return value instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * RULE-25-01 (`previous_state`) — dò accessor kiểu record {@code previousStatus()} trên giá
     * trị trả về (vd {@link com.petcare.module.auth.service.AccountSummary}) SAU khi
     * {@code proceed()} xong, cùng kỹ thuật reflection opt-in như
     * {@link #fallbackResourceIdFromResult}. Method @Auditable nào không trả về kiểu có accessor
     * này thì {@code snapshot_before} vẫn NULL (không phải mọi action đều có "trạng thái trước").
     */
    private String buildPreviousStateJson(Object result) {
        if (result == null) {
            return null;
        }
        try {
            Method getter = result.getClass().getMethod("previousStatus");
            Object value = getter.invoke(result);
            if (value == null) {
                return null;
            }
            return "{\"status\":\"" + OutboxJsonSupport.escapeJson(String.valueOf(value)) + "\"}";
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * RULE-25-01 (`ip_address`/`client_channel`) — lấy từ request HTTP hiện tại của thread (cùng
     * cách {@code AuthController} lấy {@code ipAddress} qua {@code HttpServletRequest.getRemoteAddr()}).
     * Có thể không có request nào đang xử lý (vd action gọi từ tác vụ nền/test) — bỏ qua, không lỗi.
     */
    private void populateRequestContext(AuditLog entity) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes)) {
            return;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        entity.setClientIp(request.getRemoteAddr());
        entity.setUserAgent(request.getHeader("User-Agent"));
    }

    /**
     * Fallback cho lệnh tạo mới (resource chưa tồn tại lúc vào method nên không có
     * tham số nào đánh dấu {@link AuditResourceId}) — dò accessor phổ biến trên record
     * response trả về, theo đúng thứ tự ưu tiên (record đầu tiên khớp field nào dùng field đó).
     * {@code resourceId} đặt TRƯỚC {@code storeId}, và {@code organizationId} đặt CUỐI CÙNG:
     * càng đặc hiệu (gần với chính resource bị tác động) càng ưu tiên trước — 1 response như
     * {@code StoreResourceResponse} có cả {@code resourceId} (chính resource) lẫn {@code storeId}
     * (Store cha) cùng lúc, nếu xét {@code storeId} trước sẽ ghi nhầm {@code resource_id} = ID
     * Store cha thay vì ID resource thật (vi phạm RULE-25-01 — entity_id phải đúng resource bị
     * tác động). Cùng lý do, response như {@code StoreResponse} có cả {@code storeId} (chính
     * resource) lẫn {@code organizationId} (Org cha) — xét {@code organizationId} trước sẽ ghi
     * nhầm tương tự, nên nó luôn đứng cuối. Không ảnh hưởng {@code CreateOrganization} (response
     * chỉ có field {@code organizationId}, không có accessor nào khác ở trên nó) hay
     * {@code CreateStaff}/{@code CreateCustomer} (response chỉ có {@code accountId}).
     */
    private String fallbackResourceIdFromResult(Object result) {
        if (result == null) {
            return null;
        }
        for (String accessor : new String[] {"id", "accountId", "userId", "resourceId", "storeId", "organizationId"}) {
            try {
                Method getter = result.getClass().getMethod(accessor);
                Object value = getter.invoke(result);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (ReflectiveOperationException ignored) {
                // Record không có accessor này — thử tiếp accessor sau.
            }
        }
        return null;
    }

    /** Chỉ tham số đánh dấu tường minh {@link AuditDetail} mới lọt vào snapshot — xem javadoc annotation. */
    private String buildDetailJson(Parameter[] parameters, Object[] args) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i++) {
            AuditDetail detail = parameters[i].getAnnotation(AuditDetail.class);
            if (detail != null && args[i] != null) {
                details.put(detail.value(), args[i]);
            }
        }
        if (details.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(OutboxJsonSupport.escapeJson(entry.getKey())).append("\":\"")
                    .append(OutboxJsonSupport.escapeJson(String.valueOf(entry.getValue()))).append('"');
        }
        return sb.append('}').toString();
    }
}
