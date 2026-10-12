package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.EmailChangeRequest;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.ReactivateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.service.StaffAdminService;
import com.petcare.module.identity.service.StaffQueryService;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * {@code /api/staff/**} của identity-v1 — quản lý tài khoản nhân viên (UC08; docs/adr/0029, 0030, 0031, 0033, 0034).
 * Chỉ ADMIN,
 * SUPER_MANAGER, BRANCH_MANAGER (role khác → 403 {@code ACCESS_DENIED}, không audit); đổi chức vụ và điều chuyển chỉ
 * SUPER_MANAGER (BR-QT-05, 06). Phạm vi chi tiết và phân cấp kiểm ở service. Bị chặn khi người gọi còn phải đổi mật
 * khẩu lần đầu (BR-TK-17, {@code MustChangePasswordInterceptor}). Hiện có: danh sách (#15), tạo (#16), chi tiết (#17),
 * đổi chức vụ (#18), điều chuyển (#19), vô hiệu hóa (#20), kích hoạt lại (#21), gửi lại mật khẩu ban đầu (#22), sửa
 * email (#23 gửi OTP, #24 xác nhận).
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    static final String STAFF_ADMINS = "hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER')";
    static final String SUPER_MANAGER_ONLY = "hasRole('SUPER_MANAGER')";

    private final StaffQueryService staffQueries;
    private final StaffAdminService staffAdmin;

    public StaffController(StaffQueryService staffQueries, StaffAdminService staffAdmin) {
        this.staffQueries = staffQueries;
        this.staffAdmin = staffAdmin;
    }

    /** identity-v1 #15. */
    @GetMapping
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<PageResponse<StaffResponse>> list(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(staffQueries.listStaff(branchId, role, status, q, page, size));
    }

    /** identity-v1 #16 (Tài khoản#4). Mật khẩu tạm gửi qua email; response không có mật khẩu (BR-QT-02). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        return ApiResponse.created(staffAdmin.createStaff(request), "Đã tạo tài khoản nhân viên");
    }

    /** identity-v1 #17. */
    @GetMapping("/{id}")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(staffQueries.getStaff(id));
    }

    /** identity-v1 #18 (BR-QT-04, 05). Chức vụ mới trùng chức vụ hiện tại → 200 không đổi gì (docs/adr/0031). */
    @PostMapping("/{id}/change-role")
    @PreAuthorize(SUPER_MANAGER_ONLY)
    public ApiResponse<StaffResponse> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.ok(staffAdmin.changeStaffRole(id, request), "Đã đổi chức vụ");
    }

    /** identity-v1 #19 (BR-QT-03, 04, 06, 08). Đã ở chi nhánh đích → 200 không đổi gì (docs/adr/0031). */
    @PostMapping("/{id}/transfer")
    @PreAuthorize(SUPER_MANAGER_ONLY)
    public ApiResponse<StaffResponse> transfer(@PathVariable Long id,
            @Valid @RequestBody TransferStaffRequest request) {
        return ApiResponse.ok(staffAdmin.transferStaff(id, request), "Đã điều chuyển nhân viên");
    }

    /**
     * identity-v1 #20 (Tài khoản#5; BR-QT-04, 07, 08, 09, 12, BR-TK-11). Không body. Hủy mọi phiên của nhân viên;
     * đã {@code DISABLED} → 409 (docs/adr/0033).
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> disable(@PathVariable Long id) {
        return ApiResponse.ok(staffAdmin.disableStaff(id), "Đã vô hiệu hóa nhân viên");
    }

    /**
     * identity-v1 #21 (Tài khoản#6; BR-QT-02, 03, 10, 12). Body không bắt buộc: thiếu = giữ chi nhánh hiện tại. Mật khẩu
     * tạm mới gửi qua email, response không có mật khẩu; đang {@code ACTIVE} → 409 (docs/adr/0033).
     */
    @PostMapping("/{id}/reactivate")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> reactivate(@PathVariable Long id,
            @Valid @RequestBody(required = false) ReactivateStaffRequest request) {
        return ApiResponse.ok(staffAdmin.reactivateStaff(id, request), "Đã kích hoạt lại nhân viên");
    }

    /**
     * identity-v1 #23 (BR-TK-01, 04, 07, 16, BR-QT-01). Gửi OTP tới email mới; mọi trạng thái của nhân viên, không audit
     * (docs/adr/0034).
     */
    @PostMapping("/{id}/email-change")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<OtpSentResponse> startEmailChange(@PathVariable Long id,
            @Valid @RequestBody EmailChangeRequest request) {
        return ApiResponse.ok(staffAdmin.startEmailChange(id, request), "Đã gửi mã xác thực tới email mới");
    }

    /**
     * identity-v1 #24 (BR-TK-01, 05, 06, 16). Đổi email, thông báo tới email cũ và mới, ghi audit; không hủy phiên
     * (docs/adr/0034).
     */
    @PostMapping("/{id}/email-change/confirm")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> confirmEmailChange(@PathVariable Long id,
            @Valid @RequestBody OtpCodeRequest request) {
        return ApiResponse.ok(staffAdmin.confirmEmailChange(id, request), "Đã đổi email nhân viên");
    }

    /** identity-v1 #22. 204 không body (docs/api/00-method.md §3.4). */
    @PostMapping("/{id}/resend-initial-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(STAFF_ADMINS)
    public void resendInitialPassword(@PathVariable Long id) {
        staffAdmin.resendInitialPassword(id);
    }
}
