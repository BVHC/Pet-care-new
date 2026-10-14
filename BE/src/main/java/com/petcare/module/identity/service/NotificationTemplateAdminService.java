package com.petcare.module.identity.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.NotificationTemplateResponse;
import com.petcare.module.identity.dto.UpdateTemplateRequest;
import com.petcare.module.identity.entity.NotificationTemplate;
import com.petcare.module.identity.mapper.NotificationTemplateAdminMapper;
import com.petcare.module.identity.repository.NotificationTemplateRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC10 — xem, sửa, khôi phục mẫu thông báo (identity-v1 #33, #34, #35; BR-QT-14, 15; docs/adr/0037). Không có facade:
 * không BCrypt, không cache (worker ST20 đọc mẫu thẳng từ DB lúc gửi), không có 403 phải audit.
 *
 * <p>{@link #updateTemplate}, {@link #restoreTemplate}: mỗi method một transaction, không {@code noRollbackFor}, không
 * {@code catch} — mọi lỗi rollback cả UPDATE lẫn audit; client chỉ nhận 200 khi cả hai đã commit. Thứ tự:
 * <ol>
 *   <li>khóa dòng ({@code FOR NO KEY UPDATE}) trước mọi lần đọc dòng đó (docs/adr/0029 mục 4) → không có → 404;</li>
 *   <li>(#34) quét biến của tiêu đề và nội dung theo {@code allowed_vars} / {@code required_vars} của dòng đã khóa →
 *       400 BR-QT-14 nêu đủ mọi biến lỗi;</li>
 *   <li>nội dung không đổi → không UPDATE nhưng vẫn audit, {@code before} = {@code after} (BR-QT-15 "mọi thao tác");</li>
 *   <li>ghi → {@code flush()} (UPDATE chạy trong method, lỗi được dịch đúng loại) → audit → map response.</li>
 * </ol>
 */
@Service
public class NotificationTemplateAdminService {

    static final String NOT_FOUND_TYPE = "mẫu thông báo";
    static final String RULE = "BR-QT-14";
    static final String ENTITY = "notification_templates";

    private final NotificationTemplateRepository templates;
    private final NotificationTemplateAdminMapper mapper;
    private final AuditRecorder audit;
    private final BranchScope branchScope;

    public NotificationTemplateAdminService(NotificationTemplateRepository templates,
            NotificationTemplateAdminMapper mapper, AuditRecorder audit, BranchScope branchScope) {
        this.templates = templates;
        this.mapper = mapper;
        this.audit = audit;
        this.branchScope = branchScope;
    }

    /** identity-v1 #33: mọi mẫu, sắp theo mã. */
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> listTemplates() {
        return templates.findAll(Sort.by("code")).stream().map(mapper::toResponse).toList();
    }

    /** identity-v1 #34. {@code request} đã qua Bean Validation (tiêu đề, nội dung không rỗng; tiêu đề ≤ 255). */
    @Transactional
    public NotificationTemplateResponse updateTemplate(String code, UpdateTemplateRequest request) {
        NotificationTemplate row = lock(code);
        checkVariables(row, request.subject(), request.body());
        Map<String, Object> before = snapshot(row);
        if (!row.hasContent(request.subject(), request.body())) {
            row.changeContent(request.subject(), request.body(), branchScope.current().accountId());
            templates.flush();
        }
        record(QtAuditActions.NOTIFICATION_TEMPLATE_UPDATED, before, row);
        return mapper.toResponse(row);
    }

    /** identity-v1 #35. Bản mặc định không quét lại: seed luôn hợp lệ (docs/adr/0037 mục 5). */
    @Transactional
    public NotificationTemplateResponse restoreTemplate(String code) {
        NotificationTemplate row = lock(code);
        Map<String, Object> before = snapshot(row);
        if (!row.isDefault()) {
            row.restoreDefault(branchScope.current().accountId());
            templates.flush();
        }
        record(QtAuditActions.NOTIFICATION_TEMPLATE_RESTORED, before, row);
        return mapper.toResponse(row);
    }

    private NotificationTemplate lock(String code) {
        return templates.findByCodeForUpdate(code)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, code));
    }

    /** BR-QT-14: chỉ biến có sẵn, đủ biến bắt buộc; gom mọi lỗi vào một message, nêu đủ tên biến. */
    private static void checkVariables(NotificationTemplate row, String subject, String body) {
        List<String> allowed = row.getAllowedVars();
        TemplateVariables.Scan subjectScan = TemplateVariables.scan(subject, allowed);
        TemplateVariables.Scan bodyScan = TemplateVariables.scan(body, allowed);
        Set<String> used = new LinkedHashSet<>(subjectScan.used());
        used.addAll(bodyScan.used());
        List<String> missing = row.getRequiredVars().stream().filter(name -> !used.contains(name)).toList();
        if (subjectScan.valid() && bodyScan.valid() && missing.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        describe("Tiêu đề", subjectScan, problems);
        describe("Nội dung", bodyScan, problems);
        if (!subjectScan.unknown().isEmpty() || !bodyScan.unknown().isEmpty()) {
            problems.add("Biến dùng được: " + braces(allowed));
        }
        if (!missing.isEmpty()) {
            problems.add("Mẫu thiếu biến bắt buộc: " + braces(missing));
        }
        throw new BusinessRuleViolationException(RULE, String.join("; ", problems));
    }

    private static void describe(String part, TemplateVariables.Scan scan, List<String> problems) {
        if (!scan.unknown().isEmpty()) {
            problems.add(part + " dùng biến không có: " + String.join(", ", scan.unknown()));
        }
        if (scan.strayBrace()) {
            problems.add(part + " có dấu { hoặc } lẻ — dấu ngoặc nhọn chỉ dùng để viết biến dạng {ten_bien}");
        }
    }

    private static String braces(List<String> names) {
        return names.isEmpty() ? "(không có)" : names.stream().map(name -> "{" + name + "}")
                .collect(Collectors.joining(", "));
    }

    private void record(String action, Map<String, Object> before, NotificationTemplate row) {
        audit.record(AuditEntry.of(action).entity(ENTITY, null).before(before).after(snapshot(row)));
    }

    private static Map<String, Object> snapshot(NotificationTemplate row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", row.getCode());
        snapshot.put("subject", row.getSubject());
        snapshot.put("body", row.getBody());
        return snapshot;
    }
}
