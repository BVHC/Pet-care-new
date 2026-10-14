package com.petcare.module.identity.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.AuditLogEntryResponse;
import com.petcare.module.identity.dto.AuditLogFilter;
import com.petcare.module.identity.service.AuditLogQueryService;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.constraints.Size;

/**
 * {@code /api/audit-logs} của identity-v1 — xem nhật ký audit (UC11, #36; BR-QT-15, 16; docs/adr/0038). Chỉ ADMIN (role
 * khác → 403 {@code ACCESS_DENIED}, không audit); chỉ có GET — không endpoint sửa / xóa (BR-QT-16), method khác → 405.
 * Bị chặn khi ADMIN còn phải đổi mật khẩu lần đầu (BR-TK-17). Sai kiểu tham số / quá 50 ký tự chạy trước
 * {@code @PreAuthorize} như mọi endpoint (docs/adr/0028); ba luật chéo tham số kiểm ở service, sau 403.
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final AuditLogQueryService auditLogs;

    public AuditLogController(AuditLogQueryService auditLogs) {
        this.auditLogs = auditLogs;
    }

    /**
     * identity-v1 #36. So khớp chính xác, khoảng {@code [from, to]} đóng, {@code from} / {@code to} ISO 8601 có offset
     * ({@code +} phải encode thành {@code %2B}). 50 = {@code VARCHAR(50)} của {@code action}, {@code entity_type}
     * (erd §1).
     */
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<PageResponse<AuditLogEntryResponse>> list(
            @RequestParam(required = false) Long actorAccountId,
            @RequestParam(required = false) @Size(max = 50, message = "Mã hành động tối đa 50 ký tự") String action,
            @RequestParam(required = false) @Size(max = 50, message = "Loại đối tượng tối đa 50 ký tự")
            String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditLogFilter filter = AuditLogFilter.of(actorAccountId, action, entityType, entityId, from, to);
        return ApiResponse.ok(auditLogs.listAuditLogs(filter, page, size));
    }
}
