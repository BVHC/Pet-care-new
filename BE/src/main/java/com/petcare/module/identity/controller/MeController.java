package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.ChangePasswordRequest;
import com.petcare.module.identity.dto.MeResponse;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.dto.UpdateStaffProfileRequest;
import com.petcare.module.identity.service.ChangePasswordService;
import com.petcare.module.identity.service.MeService;
import com.petcare.module.identity.service.StaffProfileService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/**
 * {@code /api/me/**} của identity-v1 — thao tác của chính người đang đăng nhập (cần token). Hiện có: thông tin người
 * đang đăng nhập (UC06), đổi mật khẩu (UC05) — mọi role; sửa hồ sơ nhân viên (UC06) — chỉ nhân viên A03–A08.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService me;
    private final ChangePasswordService changePasswords;
    private final StaffProfileService staffProfiles;

    public MeController(MeService me, ChangePasswordService changePasswords, StaffProfileService staffProfiles) {
        this.me = me;
        this.changePasswords = changePasswords;
        this.staffProfiles = staffProfiles;
    }

    /**
     * identity-v1 #8. Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}) để FE đọc
     * {@code account.mustChangePassword}. Không nhận tham số: luôn trả về chủ token.
     */
    @GetMapping
    public ApiResponse<MeResponse> getMe() {
        return ApiResponse.ok(me.getMe());
    }

    /**
     * UC05 (docs/adr/0022). Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}); thành công thì gỡ cờ bắt
     * đổi mật khẩu và hủy mọi phiên khác, phiên đang dùng giữ nguyên. 204 không có body (docs/api/00-method.md §3.4).
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        changePasswords.changePassword(request);
    }

    /**
     * identity-v1 #10 (UC06, docs/adr/0026). Khách sửa hồ sơ ở module customer nên bị 403 ở đây. Bị chặn khi còn phải
     * đổi mật khẩu lần đầu (BR-TK-17, không có trong danh sách miễn của {@code MustChangePasswordInterceptor}).
     */
    @PatchMapping("/staff-profile")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')")
    public ApiResponse<StaffProfileResponse> updateStaffProfile(
            @Valid @RequestBody UpdateStaffProfileRequest request) {
        return ApiResponse.ok(staffProfiles.updateStaffProfile(request), "Đã cập nhật hồ sơ");
    }
}
