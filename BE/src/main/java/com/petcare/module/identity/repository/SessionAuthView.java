package com.petcare.module.identity.repository;

import java.time.Instant;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

/** Kết quả một query JOIN {@code sessions} + {@code accounts} + {@code staff_profiles} để xác thực phiên. */
public record SessionAuthView(
        Long sessionId,
        Long accountId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt,
        String email,
        Role role,
        AccountStatus status,
        boolean locked,
        boolean mustChangePassword,
        Instant lastSeenAt,
        Long branchId) {
}
