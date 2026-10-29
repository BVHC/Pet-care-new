package com.petcare.module.boarding.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bản bổ sung nhật ký (boarding-v1 {@code CareLogAddendum}). */
public record CareLogAddendumResponse(
        @Schema(description = "Mã bản bổ sung", example = "4")
        Long addendumId,

        @Schema(description = "Nội dung", example = "Chiều bé ăn lại bình thường")
        String content,

        @Schema(description = "Tài khoản nhân viên thêm", example = "12")
        Long createdBy,

        @Schema(description = "Thời điểm thêm", example = "2026-11-03T10:15:00Z")
        Instant createdAt) {
}
