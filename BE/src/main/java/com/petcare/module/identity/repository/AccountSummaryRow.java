package com.petcare.module.identity.repository;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

/**
 * Projection của {@code GET /api/accounts} (identity-v1 #28, docs/adr/0035): đúng 6 cột của {@code AccountSummary}.
 * Không SELECT {@code password_hash}, {@code locked_reason}, {@code last_seen_at}, bộ đếm sai — những cột đó không bao
 * giờ đi qua đường này.
 */
public record AccountSummaryRow(
        Long id,
        String email,
        Role role,
        AccountStatus status,
        boolean locked,
        boolean mustChangePassword) {
}
