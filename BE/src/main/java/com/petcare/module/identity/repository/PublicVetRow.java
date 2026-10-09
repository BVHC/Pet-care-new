package com.petcare.module.identity.repository;

/** Hồ sơ VET công khai (BR-TK-20, UC15) — projection, không nạp entity (docs/adr/0024). */
public record PublicVetRow(
        Long accountId,
        String fullName,
        String avatarUrl,
        String specialty,
        String bio,
        Long branchId) {
}
