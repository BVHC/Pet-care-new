package com.petcare.module.identity.dto;

/**
 * Hồ sơ bác sĩ công khai (identity-v1 {@code PublicVet}, {@code GET /api/public/vets}, UC15). Chỉ dữ liệu được phép
 * công khai theo BR-TK-20 — không có email, SĐT, trạng thái hay {@code last_seen_at}. {@code branchName} lấy từ
 * {@code BranchQueryApi} (chi nhánh {@code ACTIVE}, BR-CK-01; docs/adr/0026).
 */
public record PublicVetResponse(
        Long accountId,
        String fullName,
        String avatarUrl,
        String specialty,
        String bio,
        Long branchId,
        String branchName) {
}
