package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/google} (identity-v1 #40, UC01 + UC03, docs/adr/0152). Chỉ kiểm hình thức; token kiểm
 * ở {@code GoogleIdentityVerifier}, BR-TK-02 kiểm ở service và chỉ khi phải tạo tài khoản. {@code phone} cùng định
 * dạng với {@link RegisterAccountRequest}: {@code null} hợp lệ, chuỗi rỗng thì 400.
 */
public record GoogleLoginRequest(
        @Schema(description = "ID token Google (credential do Google Identity Services trả về)",
                example = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl")
        @NotBlank(message = "Thiếu mã đăng nhập Google")
        @Size(max = 4096, message = "Mã đăng nhập Google tối đa 4096 ký tự")
        String credential,

        @Schema(description = "Xác nhận từ 18 tuổi trở lên (BR-TK-02); chỉ dùng khi tạo tài khoản", example = "true")
        Boolean isAdult,

        @Schema(description = "Đồng ý điều khoản sử dụng (BR-TK-02); chỉ dùng khi tạo tài khoản", example = "true")
        Boolean termsAccepted,

        @Schema(description = "SĐT (không bắt buộc); chỉ dùng khi tạo tài khoản, để gợi ý liên kết hồ sơ tại quầy "
                + "(BR-TK-19)",
                example = "0901234567")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone) {

    @Override
    public String toString() {
        return "GoogleLoginRequest[credential=***, isAdult=" + isAdult + ", termsAccepted=" + termsAccepted
                + ", phone=" + phone + "]";
    }
}
