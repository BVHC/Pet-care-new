package com.petcare.module.identity.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Đổi mật khẩu (UC05, docs/adr/0022): kiểm BR-TK-03 cho mật khẩu mới rồi mới mã hóa. Gom thành một chỗ để
 * <b>không có đường nào gọi {@code encode} khi chưa qua chính sách</b> — BCrypt ném lỗi (→ 500) với chuỗi &gt; 72 byte
 * (docs/adr/0009). Chỉ gọi khi {@code currentPassword} đã khớp hash hiện tại: lúc đó so chuỗi
 * {@code newPassword.equals(currentPassword)} là đúng tuyệt đối cho "trùng mật khẩu hiện tại" (cả hai ≤ 72 byte), không
 * cần BCrypt lần thứ ba.
 */
@Component
public class NewPasswordHasher {

    static final String MSG_SAME_AS_CURRENT = "Mật khẩu mới không được trùng mật khẩu hiện tại";

    private final PasswordPolicy policy;
    private final PasswordEncoder passwordEncoder;

    public NewPasswordHasher(PasswordPolicy policy, PasswordEncoder passwordEncoder) {
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
    }

    /** BR-TK-03 (chính sách, rồi không trùng mật khẩu hiện tại), sau đó BCrypt. Vi phạm → 400, chưa ghi gì. */
    public String hash(String currentPassword, String newPassword) {
        policy.check(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new BusinessRuleViolationException("BR-TK-03", MSG_SAME_AS_CURRENT);
        }
        return passwordEncoder.encode(newPassword);
    }
}
