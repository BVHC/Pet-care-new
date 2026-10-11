package com.petcare.module.identity.service;

/**
 * Thao tác quản trị nhân viên được {@link StaffHierarchyPolicy} kiểm phân cấp (UC08, BR-QT-01, 05, 06, 07, 10,
 * BR-QT-02, BR-TK-16; docs/adr/0029). Tên lấy từ cột <i>Sự kiện</i> của 03 Tài khoản (#4 tạo, #5 vô hiệu hóa, #6 kích
 * hoạt lại) và các thao tác của UC08. Khóa / mở khóa (UC09) không ở đây: chỉ ADMIN, chặn bằng {@code @PreAuthorize}.
 */
public enum StaffAction {
    CREATE,
    CHANGE_ROLE,
    TRANSFER,
    DISABLE,
    REACTIVATE,
    RESEND_INITIAL_PASSWORD,
    CHANGE_EMAIL
}
