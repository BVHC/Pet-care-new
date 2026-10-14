package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/register} (identity-v1 {@code RegisterRequest}, UC01). Chỉ kiểm hình thức; BR-TK-01,
 * 02, 03 kiểm ở service (convention 06). Message tiếng Việt viết thẳng ở annotation vì repo không có bundle
 * {@code ValidationMessages}.
 */
public record RegisterAccountRequest(
        @Schema(description = "Email đăng nhập, xác thực bằng OTP", example = "lan.nguyen@gmail.com")
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @Schema(description = "Mật khẩu: tối thiểu 8 ký tự [CFG], có cả chữ và số (BR-TK-03)",
                example = "MatKhau123",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mật khẩu không được để trống")
        String password,

        @Schema(description = "Họ tên trên hồ sơ khách online", example = "Nguyễn Thị Lan")
        @NotBlank(message = "Họ tên không được để trống")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        String fullName,

        /** Không bắt buộc, không kiểm trùng (BR-TK-01, v16); lưu ở hồ sơ khách, không lưu ở tài khoản. */
        @Schema(description = "SĐT (không bắt buộc, không kiểm trùng); dùng để gợi ý liên kết hồ sơ tại quầy "
                + "(BR-TK-19)",
                example = "0901234567")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone,

        @Schema(description = "Xác nhận từ 18 tuổi trở lên (BR-TK-02), phải là true", example = "true")
        @NotNull(message = "Thiếu xác nhận đủ 18 tuổi")
        Boolean isAdult,

        @Schema(description = "Đồng ý điều khoản sử dụng (BR-TK-02), phải là true", example = "true")
        @NotNull(message = "Thiếu xác nhận đồng ý điều khoản sử dụng")
        Boolean termsAccepted) {

    @Override
    public String toString() {
        return "RegisterAccountRequest[email=" + email + ", password=***, fullName=" + fullName + ", phone=" + phone
                + ", isAdult=" + isAdult + ", termsAccepted=" + termsAccepted + "]";
    }
}
