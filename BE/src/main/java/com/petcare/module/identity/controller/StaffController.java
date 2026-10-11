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
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.service.StaffAdminService;
import com.petcare.module.identity.service.StaffQueryService;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * {@code /api/staff/**} của identity-v1 — quản lý tài khoản nhân viên (UC08; docs/adr/0029, 0030). Chỉ ADMIN,
 * SUPER_MANAGER, BRANCH_MANAGER (role khác → 403 {@code ACCESS_DENIED}, không audit); phạm vi chi tiết và phân cấp
 * kiểm ở service. Bị chặn khi người gọi còn phải đổi mật khẩu lần đầu (BR-TK-17, {@code MustChangePasswordInterceptor}).
 * Hiện có: danh sách (#15), tạo (#16), chi tiết (#17), gửi lại mật khẩu ban đầu (#22).
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    static final String STAFF_ADMINS = "hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER')";

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

    /** identity-v1 #22. 204 không body (docs/api/00-method.md §3.4). */
    @PostMapping("/{id}/resend-initial-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(STAFF_ADMINS)
    public void resendInitialPassword(@PathVariable Long id) {
        staffAdmin.resendInitialPassword(id);
    }
}
