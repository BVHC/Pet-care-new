package com.petcare.module.catalog.controller;

/**
 * Biểu thức {@code @PreAuthorize} của catalog-v1 (mục B): đọc mở cho mọi nhân viên A04–A08 để dùng ở POS, kê đơn,
 * tiêm; ghi chỉ SUPER_MANAGER. CUSTOMER và ADMIN không có quyền ở các endpoint này.
 */
final class CatalogAccess {

    static final String STAFF_READ =
            "hasAnyRole('SUPER_MANAGER','BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    static final String SUPER_MANAGER_WRITE = "hasRole('SUPER_MANAGER')";

    private CatalogAccess() {
    }
}
