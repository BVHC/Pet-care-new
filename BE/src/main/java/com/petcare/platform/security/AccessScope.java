package com.petcare.platform.security;

/**
 * Phạm vi dữ liệu của người đang đăng nhập (04 nguyên tắc 8, 01 A02, docs/adr/0006). Quyết định cách
 * {@link BranchScope} xử lý dữ liệu có {@code branch_id}.
 */
public enum AccessScope {

    /** ADMIN, SUPER_MANAGER: toàn chuỗi, lọc chi nhánh theo tham số truyền vào. */
    CHAIN,

    /** A05–A08: chỉ chi nhánh của mình (BR-QT-03). */
    BRANCH,

    /**
     * CUSTOMER: chỉ dữ liệu của chính mình (01 A02). Service lọc theo chủ sở hữu, không đi qua {@link BranchScope};
     * gọi {@link BranchScope} với phạm vi này bị từ chối 403.
     */
    OWNER
}
