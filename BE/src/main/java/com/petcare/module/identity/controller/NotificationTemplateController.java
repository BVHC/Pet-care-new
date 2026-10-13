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
import com.petcare.platform.model.ApiResponse;

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
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<List<NotificationTemplateResponse>> list() {
        return ApiResponse.ok(templates.listTemplates());
    }

    /**
     * identity-v1 #34. Biến lạ / dấu ngoặc lẻ / thiếu biến bắt buộc → 400 BR-QT-14 nêu đủ tên biến; mã lạ → 404; trùng
     * nội dung hiện tại → 200, không ghi dòng nhưng vẫn audit. Có hiệu lực từ lần gửi đọc mẫu sau khi response trả về.
     */
    @PutMapping("/{code}")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<NotificationTemplateResponse> update(@PathVariable String code,
            @Valid @RequestBody UpdateTemplateRequest request) {
        return ApiResponse.ok(templates.updateTemplate(code, request), "Đã cập nhật mẫu thông báo");
    }

    /** identity-v1 #35. Mã lạ → 404; đã là bản mặc định → 200, không ghi dòng nhưng vẫn audit. */
    @PostMapping("/{code}/restore-default")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<NotificationTemplateResponse> restoreDefault(@PathVariable String code) {
        return ApiResponse.ok(templates.restoreTemplate(code), "Đã khôi phục mẫu mặc định");
    }
}
