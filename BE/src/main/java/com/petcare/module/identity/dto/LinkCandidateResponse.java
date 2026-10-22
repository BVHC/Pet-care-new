package com.petcare.module.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một hồ sơ tại quầy có thể liên kết (identity-v1 {@code LinkCandidate}, UC07). Họ tên đã che do module customer làm
 * (BR-TK-19 "Ng*** V** A"); identity không đọc họ tên thật. {@code hasEmail = false}: hồ sơ không có email nên không
 * liên kết online được, FE hướng dẫn ra quầy (BR-TK-19). {@code @JsonProperty("hasEmail")} giữ đúng tên trường của
 * contract (như {@code AccountSummary.isLocked}).
 */
public record LinkCandidateResponse(
        @Schema(description = "Mã hồ sơ tại quầy", example = "315")
        Long customerId,

        @Schema(description = "Họ tên đã che", example = "Ng*** V** A")
        String maskedFullName,

        @Schema(description = "Hồ sơ có email để nhận mã OTP; false thì phải liên kết tại quầy", example = "true")
        @JsonProperty("hasEmail") boolean hasEmail) {
}
