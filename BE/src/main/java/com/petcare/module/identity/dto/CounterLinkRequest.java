package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/customers/{customerId}/link-account} (identity-v1 {@code CounterLinkRequest}, UC22 —
 * BR-TK-16, 19). Chỉ kiểm hình thức (convention 06), như {@link CustomerEmailChangeRequest}; chuẩn hóa chữ thường, tìm
 * tài khoản và mọi guard BR-TK-19 ở service. {@code verificationMethod} bắt buộc nhưng không lưu ở đâu ngoài audit; giá
 * trị ngoài enum là lỗi đọc JSON ({@code MALFORMED_REQUEST}).
 */
public record CounterLinkRequest(
        @Schema(description = "Email tài khoản online của khách", example = "khach.online@gmail.com")
        @NotBlank(message = "Email tài khoản không được để trống")
        @Email(message = "Email tài khoản không đúng định dạng")
        @Size(max = 255, message = "Email tài khoản tối đa 255 ký tự")
        String accountEmail,

        @Schema(description = "Lễ tân đã đối chiếu CCCD với họ tên hồ sơ; không lưu CCCD (BR-TK-16, 19, A6)",
                example = "ID_CARD_IN_PERSON")
        @NotNull(message = "Phương thức xác minh không được để trống")
        VerificationMethod verificationMethod) {
}
