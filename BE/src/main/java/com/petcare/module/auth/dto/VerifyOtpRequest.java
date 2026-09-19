package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** docs/api/openapi/auth-v1.yaml #VerifyOtpRequest (sửa 2026-09-13 — email thay phone). */
public record VerifyOtpRequest(
        @NotBlank @Email String email,
        // VO OtpCode (05#4.1) / openapi OtpCode schema = CONFIRMED 6 chữ số — trước đây
        // chỉ @NotBlank nên mã sai định dạng lọt xuống Service, bị tính nhầm thành 1 lần
        // "nhập sai OTP" (cộng vào bộ đếm khoá RULE-01-05) thay vì 400 VALIDATION_FAILED.
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Mã OTP phải gồm đúng 6 chữ số") String otpCode
) {
}
