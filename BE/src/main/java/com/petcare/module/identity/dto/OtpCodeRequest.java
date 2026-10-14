package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body của {@code POST /api/staff/{id}/email-change/confirm} (identity-v1 {@code OtpCodeRequest}, UC08 —
 * docs/adr/0034). Chỉ kiểm hình thức; BR-TK-05, 06 kiểm ở service. Giới hạn 8 chữ số = {@code max} của
 * {@code otp.code_length} [CFG] (như {@code VerifyAccountRequest}, {@code LinkConfirmRequest}): mã sai độ dài nhưng
 * đúng định dạng vẫn tính là một lần sai. {@link #toString()} che mã để mã không vào log.
 */
public record OtpCodeRequest(
        @Schema(description = "Mã OTP đã gửi tới email mới",
                example = "482913",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "^\\d{1,8}$", message = "Mã OTP chỉ gồm tối đa 8 chữ số")
        String code) {

    @Override
    public String toString() {
        return "OtpCodeRequest[code=***]";
    }
}
