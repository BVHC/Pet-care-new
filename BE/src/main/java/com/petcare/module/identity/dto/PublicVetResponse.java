package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ bác sĩ công khai (identity-v1 {@code PublicVet}, {@code GET /api/public/vets}, UC15). Chỉ dữ liệu được phép
 * công khai theo BR-TK-20 — không có email, SĐT, trạng thái hay {@code last_seen_at}. {@code branchName} lấy từ
 * {@code BranchQueryApi} (chi nhánh {@code ACTIVE}, BR-CK-01; docs/adr/0026).
 */
public record PublicVetResponse(
        @Schema(description = "Mã tài khoản của bác sĩ", example = "18")
        Long accountId,

        @Schema(description = "Họ tên", example = "BS. Lê Minh Châu")
        String fullName,

        @Schema(description = "Ảnh đại diện (https)", example = "https://cdn.petcare.vn/avatars/18.jpg")
        String avatarUrl,

        @Schema(description = "Chuyên môn", example = "Nội khoa chó mèo")
        String specialty,

        @Schema(description = "Mô tả ngắn (tối đa vet.bio_max_length [CFG])",
                example = "10 năm kinh nghiệm điều trị bệnh nội khoa.")
        String bio,

        @Schema(description = "Chi nhánh làm việc", example = "1")
        Long branchId,

        @Schema(description = "Tên chi nhánh", example = "Pet Care Cầu Giấy")
        String branchName) {
}
