package com.petcare.module.identity.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.module.identity.dto.UpdateConfigRequest;
import com.petcare.module.identity.service.SystemConfigAdminService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/**
 * {@code /api/system-configs} của identity-v1 — xem, sửa tham số [CFG] (UC10, #31, #32; BR-QT-13; docs/adr/0036). Chỉ
 * ADMIN (role khác → 403 {@code ACCESS_DENIED}, không audit). Không có endpoint thêm / xóa tham số: danh mục cố định theo
 * {@code ConfigKey} + seed V2 (docs/adr/0004). Bị chặn khi ADMIN còn phải đổi mật khẩu lần đầu (BR-TK-17).
 */
@RestController
@RequestMapping("/api/system-configs")
public class SystemConfigController {

    static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final SystemConfigAdminService systemConfigs;

    public SystemConfigController(SystemConfigAdminService systemConfigs) {
        this.systemConfigs = systemConfigs;
    }

    /** identity-v1 #31: mảng (không phân trang), sắp theo {@code key}. */
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<List<SystemConfigResponse>> list() {
        return ApiResponse.ok(systemConfigs.listConfigs());
    }

    /**
     * identity-v1 #32. Ngoài kiểu / min–max / ràng buộc chéo → 400 BR-QT-13 kèm khoảng cho phép; key lạ → 404; trùng giá
     * trị hiện tại → 200 không ghi. Có hiệu lực cho mọi giao dịch tạo sau khi response trả về.
     */
    @PutMapping("/{key}")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SystemConfigResponse> update(@PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ApiResponse.ok(systemConfigs.updateConfig(key, request.value()), "Đã cập nhật tham số");
    }
}
