package com.petcare.module.identity.repository;

import java.time.Instant;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

/**
 * Một dòng JOIN {@code staff_profiles} + {@code accounts} cho {@code StaffDirectoryApi} (docs/adr/0024). Là projection
 * (JPQL constructor expression) để không nạp entity {@code Account} vào persistence context của transaction bên gọi
 * (cùng lý do {@link AccountCredential}).
 */
public record StaffRow(
        Long accountId,
        String fullName,
        Role role,
        Long branchId,
        AccountStatus status,
        boolean locked,
        Instant lastSeenAt) {
}
