package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Body của {@code POST /api/me/password} (identity-v1 {@code ChangePasswordRequest}, UC05). Chỉ kiểm hình thức; lỗi ở
 * đây không tính là một lần nhập sai mật khẩu hiện tại (docs/adr/0022). Không giới hạn độ dài ở đây: {@code @Size} đếm
 * ký tự chứ không đếm byte — mật khẩu hiện tại &gt; 72 byte là sai mật khẩu, mật khẩu mới &gt; 72 byte là BR-TK-03, cả
 * hai xử lý ở service (như {@link LoginRequest}, {@link RegisterAccountRequest}). Không cắt khoảng trắng.
 */
public record ChangePasswordRequest(
        @Schema(description = "Mật khẩu hiện tại", example = "MatKhauCu123", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mật khẩu hiện tại không được để trống")
        String currentPassword,

        @Schema(description = "Mật khẩu mới: tối thiểu 8 ký tự [CFG], có cả chữ và số, khác mật khẩu hiện tại "
                + "(BR-TK-03)",
                example = "MatKhauMoi456",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mật khẩu mới không được để trống")
        String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
