package com.petcare.module.identity.repository;

import com.petcare.module.identity.entity.AccountStatus;

/**
 * Bước đọc không khóa của đặt lại mật khẩu (UC04, docs/adr/0023): đủ để quyết định tài khoản có được đặt lại không
 * (BR-TK-12) và để so BCrypt "trùng mật khẩu hiện tại" (BR-TK-03) khi không giữ connection. Projection, không nạp
 * entity — cùng lý do {@link AccountCredential}. Quyết định ghi luôn dựa trên dữ liệu đọc lại dưới khóa.
 */
public record AccountResetState(Long id, AccountStatus status, boolean locked, String passwordHash) {

    /** BR-TK-12: chỉ tài khoản {@code ACTIVE} không bị khóa ({@code is_locked}); khóa tạm BR-TK-09 vẫn được. */
    public boolean eligible() {
        return status == AccountStatus.ACTIVE && !locked;
    }

    @Override
    public String toString() {
        return "AccountResetState[id=" + id + ", status=" + status + ", locked=" + locked + ", passwordHash=***]";
    }
}
