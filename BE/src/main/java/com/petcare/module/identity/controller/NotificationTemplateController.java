package com.petcare.module.identity.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.NotificationTemplateResponse;
import com.petcare.module.identity.dto.UpdateTemplateRequest;
import com.petcare.module.identity.service.NotificationTemplateAdminService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import jakarta.validation.Valid;

/**
 * {@code /api/notification-templates} của identity-v1 — xem, sửa, khôi phục mẫu thông báo (UC10, #33, #34, #35;
 * BR-QT-14, 15; docs/adr/0037). Chỉ ADMIN (role khác → 403 {@code ACCESS_DENIED}, không audit). Không có endpoint thêm /
 * xóa mẫu: danh mục cố định theo {@code NotificationTemplateCode} + seed migration (BR-QT-14, 06 §8 Q4). Bị chặn khi
 * ADMIN còn phải đổi mật khẩu lần đầu (BR-TK-17).
 */
@RestController
@RequestMapping("/api/notification-templates")
public class NotificationTemplateController {

    static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final NotificationTemplateAdminService templates;

    public NotificationTemplateController(NotificationTemplateAdminService templates) {
        this.templates = templates;
    }

    /** identity-v1 #33: mảng (không phân trang), sắp theo {@code code}. */
    @Operation(operationId = "listNotificationTemplates", tags = "Configuration",
            summary = "Danh sách mẫu thông báo",
            description = "UC10 · BR-QT-14. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Mọi mẫu, "
                + "sắp theo code; subject / body là bản đang dùng; isDefault = cả hai trùng từng ký tự với bản mặc "
                + "định (docs/adr/0037).")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<List<NotificationTemplateResponse>> list() {
        return ApiResponse.ok(templates.listTemplates());
    }

    /**
     * identity-v1 #34. Biến lạ / dấu ngoặc lẻ / thiếu biến bắt buộc → 400 BR-QT-14 nêu đủ tên biến; mã lạ → 404; trùng
     * nội dung hiện tại → 200, không ghi dòng nhưng vẫn audit. Có hiệu lực từ lần gửi đọc mẫu sau khi response trả về.
     */
    @Operation(operationId = "updateNotificationTemplate", tags = "Configuration",
            summary = "Sửa nội dung mẫu",
            description = "UC10 · BR-QT-14, 15. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Thứ tự "
                + "kiểm: hình thức → quyền → code lạ 404 → biến (BR-QT-14). Ngữ pháp chặt: dấu ngoặc nhọn chỉ dùng để "
                + "viết {ten} với ten thuộc allowedVars (phân biệt hoa thường); biến bắt buộc tính trên subject ∪ "
                + "body. Lưu nguyên văn (không cắt khoảng trắng, không đổi xuống dòng). Trùng nội dung hiện tại → 200, "
                + "không ghi nhưng vẫn audit (before = after). Có hiệu lực từ lần gửi đọc mẫu sau khi response trả về, "
                + "kể cả thông báo đã xếp hàng. Audit NOTIFICATION_TEMPLATE_UPDATED. Không có endpoint thêm / xóa mẫu "
                + "(docs/adr/0037).",
            parameters = {
                    @Parameter(name = "code", in = ParameterIn.PATH, example = "OTP_REGISTER",
                            description = "Ví dụ VACCINE_REMINDER")})
    @ApiErrorDoc(status = 400, description = "`VALIDATION_FAILED` thiếu / rỗng subject hoặc body, subject "
            + "quá 255 · `BR-QT-14` dùng biến không tồn tại, dấu { } lẻ hoặc thiếu biến bắt buộc (message nêu đủ "
            + "mọi biến lỗi)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PutMapping("/{code}")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<NotificationTemplateResponse> update(@PathVariable String code,
            @Valid @RequestBody UpdateTemplateRequest request) {
        return ApiResponse.ok(templates.updateTemplate(code, request), "Đã cập nhật mẫu thông báo");
    }

    /** identity-v1 #35. Mã lạ → 404; đã là bản mặc định → 200, không ghi dòng nhưng vẫn audit. */
    @Operation(operationId = "restoreNotificationTemplate", tags = "Configuration",
            summary = "Khôi phục mẫu mặc định",
            description = "UC10 · BR-QT-14, 15. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Chép "
                + "bản mặc định sang bản đang dùng. Đã là mặc định → 200, không ghi nhưng vẫn audit (before = after). "
                + "Audit NOTIFICATION_TEMPLATE_RESTORED (docs/adr/0037).",
            parameters = {
                    @Parameter(name = "code", in = ParameterIn.PATH, example = "OTP_REGISTER",
                            description = "Ví dụ VACCINE_REMINDER")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/{code}/restore-default")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<NotificationTemplateResponse> restoreDefault(@PathVariable String code) {
        return ApiResponse.ok(templates.restoreTemplate(code), "Đã khôi phục mẫu mặc định");
    }
}
