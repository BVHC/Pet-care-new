package com.petcare.module.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;

/**
 * Tóm tắt tài khoản (identity-v1 {@code AccountSummary}). {@code mustChangePassword = true}: FE chuyển màn đổi mật
 * khẩu, mọi API khác trả 400 BR-TK-17 (BR-TK-17). {@code @JsonProperty("isLocked")} giữ đúng tên trường của contract.
 */
public record AccountSummary(
        Long id,
        String email,
        Role role,
        AccountStatus status,
        @JsonProperty("isLocked") boolean isLocked,
        boolean mustChangePassword) {
}
