package com.petcare.module.identity.dto;

import com.petcare.module.identity.api.Role;

/**
 * Tham số {@code role} của {@code GET /api/staff/assignable} (identity-v1 #38; docs/adr/0089): chỉ hai chức vụ được gán
 * lượt (BR-TN-05, VET cho nhóm Khám / Tiêm, CARETAKER cho nhóm Thẩm mỹ). Giá trị ngoài hai chức vụ này, hoặc viết sai
 * hoa thường, bị Spring từ chối ở bước chuyển kiểu: 400 {@code MALFORMED_REQUEST}, cùng hình dạng với thiếu tham số.
 */
public enum AssignableRole {

    VET(Role.VET),
    CARETAKER(Role.CARETAKER);

    private final Role role;

    AssignableRole(Role role) {
        this.role = role;
    }

    public Role role() {
        return role;
    }
}
