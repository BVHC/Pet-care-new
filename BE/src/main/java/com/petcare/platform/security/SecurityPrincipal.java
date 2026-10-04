package com.petcare.platform.security;

import com.petcare.platform.audit.AuditPrincipal;

/**
 * Người đang đăng nhập, đặt vào {@code SecurityContext} sau khi phiên được xác thực. Module TK implement interface
 * này (platform không import module, convention 01). Mọi giá trị đọc từ DB ở chính request hiện tại (docs/adr/0003),
 * nên đổi chức vụ, điều chuyển chi nhánh có hiệu lực ngay ở request kế tiếp.
 */
public interface SecurityPrincipal extends AuditPrincipal {

    /** {@code sessions.id} của phiên đang dùng (đổi mật khẩu hủy "mọi phiên khác", BR-TK-14). */
    Long sessionId();

    /** Tên role cố định (04 §1): {@code CUSTOMER}, {@code ADMIN}… Authority tương ứng là {@code ROLE_<role>}. */
    String role();

    /** Chi nhánh làm việc ({@code staff_profiles.branch_id}); {@code null} với ADMIN, SUPER_MANAGER, CUSTOMER. */
    Long branchId();

    /** Phạm vi dữ liệu theo role: toàn chuỗi, một chi nhánh, hoặc chỉ dữ liệu của chính mình (docs/adr/0006). */
    AccessScope accessScope();

    /** {@code true} với A05–A08: chỉ thao tác trên dữ liệu chi nhánh của mình (04 nguyên tắc 8). */
    default boolean branchScoped() {
        return accessScope() == AccessScope.BRANCH;
    }

    /** BR-TK-17: còn phải đổi mật khẩu lần đầu. */
    boolean mustChangePassword();
}
