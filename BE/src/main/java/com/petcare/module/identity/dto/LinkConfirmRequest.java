package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/me/link/confirm} (identity-v1 {@code LinkConfirmRequest}, UC07). Chỉ kiểm hình thức;
 * BR-TK-05, 06, 19 kiểm ở service. Giới hạn 8 chữ số = {@code max} của {@code otp.code_length} [CFG] (như
 * {@code VerifyAccountRequest}): mã sai độ dài nhưng đúng định dạng vẫn tính là một lần sai.
 */
public record LinkConfirmRequest(
        @Schema(description = "Mã hồ sơ tại quầy đã nhận mã", example = "315")
        @NotNull(message = "Vui lòng chọn hồ sơ cần liên kết")
        @Positive(message = "Mã hồ sơ không hợp lệ")
        Long customerId,

        @Schema(description = "Mã OTP gửi tới email của hồ sơ tại quầy",
                example = "482913",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "^\\d{1,8}$", message = "Mã OTP chỉ gồm tối đa 8 chữ số")
        String code) {

    @Override
    public String toString() {
        return "LinkConfirmRequest[customerId=" + customerId + ", code=***]";
    }
}
