package com.petcare.module.visit.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một bản bổ sung bệnh án (visit-v1 schema {@code Addendum}, BR-KB-01; docs/adr/0066). Chỉ dựng bởi {@code MedicalRecordMapper}. */
public record AddendumResponse(
        @Schema(description = "Mã bản bổ sung", example = "301")
        Long addendumId,

        @Schema(description = "Nội dung", example = "Bổ sung: kết quả xét nghiệm máu về sau, bạch cầu tăng")
        String content,

        @Schema(description = "Bổ sung cho ghi chú nội bộ, khách không thấy", example = "false")
        @JsonProperty("isInternal") boolean isInternal,

        @Schema(description = "Người ghi", example = "45")
        Long createdBy,

        @Schema(description = "Tên người ghi", example = "BS. Trần Lan")
        String createdByName,

        @Schema(description = "Thời điểm ghi", example = "2026-10-22T09:30:00Z")
        Instant createdAt) {
}
