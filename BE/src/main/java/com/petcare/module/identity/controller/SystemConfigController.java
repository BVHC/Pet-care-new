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
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

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
    @Operation(operationId = "listSystemConfigs", tags = "Configuration",
            summary = "Danh sách tham số [CFG]",
            description = "UC10 · BR-QT-13. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Mọi tham "
                + "số (52), sắp theo key; minValue / maxValue là khoảng tĩnh, khoảng thu hẹp theo ràng buộc chéo chỉ "
                + "có trong message lỗi của #32 (docs/adr/0036).")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<List<SystemConfigResponse>> list() {
        return ApiResponse.ok(systemConfigs.listConfigs());
    }

    /**
     * identity-v1 #32. Ngoài kiểu / min–max / ràng buộc chéo → 400 BR-QT-13 kèm khoảng cho phép; key lạ → 404; trùng giá
     * trị hiện tại → 200 không ghi. Có hiệu lực cho mọi giao dịch tạo sau khi response trả về.
     */
    @Operation(operationId = "updateSystemConfig", tags = "Configuration",
            summary = "Sửa tham số",
            description = "UC10 · BR-QT-13, 15. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —. Thứ tự "
                + "kiểm: hình thức → quyền → key lạ 404 → kiểu / min–max / ràng buộc chéo (400 BR-QT-13, message ghi "
                + "khoảng cho phép thực tế; sai kiểu cũng BR-QT-13) → trùng giá trị hiện tại (sau chuẩn hóa, ví dụ 05 "
                + "= 5) → 200 không ghi, không audit. Ràng buộc chéo: visit.late_priority_minutes < "
                + "appointment.no_show_after_minutes, boarding.min_nights ≤ boarding.max_nights. value được chuẩn hóa "
                + "(INT bỏ số 0 đầu, TIME dạng HH:mm, DECIMAL không dạng mũ). Có hiệu lực cho mọi giao dịch tạo sau "
                + "khi response trả về; ghi audit SYSTEM_CONFIG_UPDATED (docs/adr/0036).",
            parameters = {
                    @Parameter(name = "key", in = ParameterIn.PATH, example = "otp.ttl_minutes",
                            description = "Ví dụ otp.ttl_minutes")})
    @ApiErrorDoc(status = 400, description = "`BR-QT-13` ngoài khoảng hợp lệ, message ghi khoảng cho phép")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PutMapping("/{key}")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SystemConfigResponse> update(@PathVariable String key,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ApiResponse.ok(systemConfigs.updateConfig(key, request.value()), "Đã cập nhật tham số");
    }
}
