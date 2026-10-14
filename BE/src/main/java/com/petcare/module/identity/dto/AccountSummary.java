package com.petcare.module.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tóm tắt tài khoản (identity-v1 {@code AccountSummary}). {@code mustChangePassword = true}: FE chuyển màn đổi mật
 * khẩu, mọi API khác trả 400 BR-TK-17 (BR-TK-17). {@code @JsonProperty("isLocked")} giữ đúng tên trường của contract.
 */
public record AccountSummary(
        @Schema(description = "Mã tài khoản", example = "42")
        Long id,

        @Schema(description = "Email đăng nhập, định danh duy nhất (BR-TK-01)", example = "lan.nguyen@petcare.vn")
        String email,

        @Schema(description = "Vai trò", example = "VET")
        Role role,

        @Schema(description = "Trạng thái tài khoản (Tài khoản, 03 §1)", example = "ACTIVE")
        AccountStatus status,

        @Schema(description = "Đang bị ADMIN khóa (BR-QT-11), độc lập với status", example = "false")
        @JsonProperty("isLocked") boolean isLocked,

        @Schema(description = "Phải đổi mật khẩu trước khi dùng chức năng khác (BR-TK-17)", example = "false")
        boolean mustChangePassword) {
}
