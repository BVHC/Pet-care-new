package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/staff/{id}/email-change} (identity-v1 {@code EmailChangeRequest}, UC08 — docs/adr/0034).
 * Chỉ kiểm hình thức (convention 06), như {@code email} của {@code CreateStaffRequest}; 255 = {@code accounts.email}
 * (erd §1). Chuẩn hóa chữ thường, trùng email hiện tại / tài khoản khác (BR-TK-01) kiểm ở service. Trường lạ trong
 * JSON bị bỏ qua.
 */
public record EmailChangeRequest(
        @Schema(description = "Email mới; mã OTP gửi tới email này (BR-TK-04, 16)",
                example = "binh.tran.moi@petcare.vn")
        @NotBlank(message = "Email mới không được để trống")
        @Email(message = "Email mới không đúng định dạng")
        @Size(max = 255, message = "Email mới tối đa 255 ký tự")
        String newEmail) {
}
