package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/customers/{customerId}/account-email-change} (identity-v1 {@code CustomerEmailChangeRequest},
 * UC22 — BR-TK-16). Chỉ kiểm hình thức (convention 06), như {@link EmailChangeRequest}; chuẩn hóa chữ thường, trùng email
 * hiện tại / tài khoản khác (BR-TK-01) kiểm ở service. {@code verificationMethod} bắt buộc nhưng không lưu ở đâu ngoài
 * audit của bước xác nhận (body #26 chỉ có {@code code}); giá trị ngoài enum là lỗi đọc JSON ({@code MALFORMED_REQUEST}).
 */
public record CustomerEmailChangeRequest(
        @Schema(description = "Email mới; mã OTP gửi tới email này (BR-TK-04, 16)",
                example = "khach.moi@gmail.com")
        @NotBlank(message = "Email mới không được để trống")
        @Email(message = "Email mới không đúng định dạng")
        @Size(max = 255, message = "Email mới tối đa 255 ký tự")
        String newEmail,

        @Schema(description = "Lễ tân đã đối chiếu CCCD với họ tên hồ sơ; không lưu CCCD (BR-TK-16, A6)",
                example = "ID_CARD_IN_PERSON")
        @NotNull(message = "Phương thức xác minh không được để trống")
        VerificationMethod verificationMethod) {
}
