package com.petcare.module.branch.controller;

/**
 * Biểu thức {@code @PreAuthorize} của branch-v1 (mục B, D): chi nhánh do SUPER_MANAGER quản lý (BR-CN-05); giờ mở
 * cửa và ngày nghỉ do BRANCH_MANAGER của chính chi nhánh đó (phạm vi chi nhánh kiểm ở service bằng
 * {@code BranchScope.check}). Đọc mở cho ADMIN, SUPER_MANAGER và nhân viên chi nhánh; CUSTOMER không có quyền.
 */
final class BranchAccess {

    static final String STAFF_READ =
            "hasAnyRole('ADMIN','SUPER_MANAGER','BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    static final String SUPER_MANAGER_WRITE = "hasRole('SUPER_MANAGER')";

    static final String BRANCH_MANAGER_WRITE = "hasRole('BRANCH_MANAGER')";

    private BranchAccess() {
    }
}
