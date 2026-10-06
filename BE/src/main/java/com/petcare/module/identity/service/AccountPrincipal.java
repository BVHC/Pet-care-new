package com.petcare.module.identity.service;

import com.petcare.module.identity.api.Role;
import com.petcare.platform.security.AccessScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Principal đặt vào {@code SecurityContext} sau khi phiên hợp lệ (docs/adr/0003). Mọi trường đọc từ DB ở request
 * hiện tại. Implement {@code AuditPrincipal} (qua {@link SecurityPrincipal}) nên {@code AuditRecorder} lấy được actor.
 */
public record AccountPrincipal(
        Long accountId,
        String email,
        Long sessionId,
        Role accountRole,
        Long branchId,
        boolean mustChangePassword) implements SecurityPrincipal {

    @Override
    public String role() {
        return accountRole.name();
    }

    /**
     * A05–A08 thuộc đúng một chi nhánh (BR-QT-03); khách chỉ thấy dữ liệu của mình (01 A02) — docs/adr/0006.
     * Switch không có {@code default}: thêm role mới thì phải chọn phạm vi mới compile được.
     */
    @Override
    public AccessScope accessScope() {
        return switch (accountRole) {
            case ADMIN, SUPER_MANAGER -> AccessScope.CHAIN;
            case BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER -> AccessScope.BRANCH;
            case CUSTOMER -> AccessScope.OWNER;
        };
    }
}
