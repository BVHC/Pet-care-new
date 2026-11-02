package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/refresh} (identity-v1 {@code RefreshRequest}, docs/adr/0091). Chỉ kiểm hình thức;
 * lỗi ở đây không chạm DB.
 */
public record RefreshTokenRequest(
        @Schema(description = "Refresh token nhận lúc đăng nhập hoặc lần refresh trước",
                example = "Zm9vYmFyLXJlZnJlc2gtdG9rZW4tZXhhbXBsZS0wMDAwMDA", maxLength = 128)
        @NotBlank(message = "Refresh token không được để trống")
        @Size(max = 128, message = "Refresh token tối đa 128 ký tự")
        String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=***]";
    }
}
