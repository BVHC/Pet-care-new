package com.petcare.module.identity.repository;

/**
 * Bước đọc không khóa của đăng nhập (docs/adr/0019 mục 4): chỉ {@code id} và {@code password_hash}. Là projection
 * (JPQL constructor expression) để <b>không</b> nạp entity {@code Account} vào persistence context — nếu nạp, truy
 * vấn {@code findByIdForUpdate} sau đó trả lại đúng instance cũ mà không đọc lại bộ đếm.
 */
public record AccountCredential(Long id, String passwordHash) {

    @Override
    public String toString() {
        return "AccountCredential[id=" + id + ", passwordHash=***]";
    }
}
