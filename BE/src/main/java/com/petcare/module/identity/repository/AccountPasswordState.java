package com.petcare.module.identity.repository;

import java.time.Instant;

/**
 * Bước đọc không khóa của đổi mật khẩu (docs/adr/0022): {@code password_hash} để so BCrypt khi không giữ connection,
 * {@code locked_until} để từ chối sớm lúc đang khóa tạm (không chạy BCrypt). Projection để không nạp entity
 * {@code Account} — như {@link AccountCredential}.
 */
public record AccountPasswordState(String passwordHash, Instant lockedUntil) {

    @Override
    public String toString() {
        return "AccountPasswordState[passwordHash=***, lockedUntil=" + lockedUntil + "]";
    }
}
