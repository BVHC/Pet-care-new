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
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

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
    @Operation(operationId = "listAuditLogs", tags = "Audit",
            summary = "Xem nhật ký audit",
            description = "UC11 · BR-QT-15, 16. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Chỉ "
                + "đọc; không có endpoint sửa / xóa (BR-QT-16): method khác → 405. Xem không ghi audit. Mọi dòng của "
                + "mọi người (kể cả hệ thống, actor null). Sắp `createdAt` giảm dần rồi `id` giảm dần; khoảng [from, "
                + "to] tính cả hai mốc; giá trị lọc không tồn tại → trang rỗng; action / entityType rỗng = không lọc; "
                + "size ngoài 1–100 bị kẹp, page âm = 0. `beforeData` / `afterData` là JSON object đúng như lúc ghi "
                + "(thứ tự khóa không cố định), null khi không có. `totalElements` khớp `content` trong cùng một "
                + "response (docs/adr/0038).",
            parameters = {
                    @Parameter(name = "actorAccountId", in = ParameterIn.QUERY, example = "7",
                            description = "Người thực hiện (khớp đúng)"),
                    @Parameter(name = "action", in = ParameterIn.QUERY, example = "STAFF_CREATED",
                            description = "Mã hành động, tối đa 50 ký tự (khớp đúng). Không kèm actorAccountId "
                                    + "hay entityType + entityId thì bắt buộc from + to cách nhau ≤ 31 ngày"),
                    @Parameter(name = "entityType", in = ParameterIn.QUERY, example = "accounts",
                            description = "Tên bảng, tối đa 50 ký tự (khớp đúng)"),
                    @Parameter(name = "entityId", in = ParameterIn.QUERY, example = "42",
                            description = "Bắt buộc kèm entityType"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01T00:00:00+07:00",
                            description = "Từ thời điểm (tính cả mốc); `+` của offset phải encode `%2B`"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-14T23:59:59+07:00",
                            description = "Đến thời điểm (tính cả mốc); không được trước from"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "`MALFORMED_REQUEST` tham số sai kiểu (from / to thiếu "
            + "offset, id không phải số nguyên) · `VALIDATION_FAILED` action / entityType quá 50 ký tự, `to` trước "
            + "`from`, `entityId` không kèm `entityType`, lọc `action` không kèm người / đối tượng mà thiếu from / "
            + "to hoặc cách nhau quá 31 ngày (message nêu đủ lỗi, theo tên tham số)")
    @ApiErrorDoc(status = 403, description = "Không phải ADMIN (không ghi audit)")
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
