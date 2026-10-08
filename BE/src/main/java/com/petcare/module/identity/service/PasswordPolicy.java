package com.petcare.module.identity.service;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.PasswordConfig;

/**
 * BR-TK-03 — chính sách mật khẩu, dùng chung cho đăng ký (UC01), đổi mật khẩu (UC05) và đặt lại mật khẩu (UC04).
 * Độ dài tối thiểu đọc từ [CFG] nên kiểm ở service, không bằng annotation (convention 06). Phải gọi <b>trước</b>
 * {@code PasswordEncoder.encode}: BCrypt ném lỗi (→ 500) với chuỗi &gt; 72 byte (docs/adr/0009).
 */
@Component
public class PasswordPolicy {

    private final SystemConfigApi configs;

    public PasswordPolicy(SystemConfigApi configs) {
        this.configs = configs;
    }

    /** Tối thiểu {@code password.min_length} [CFG] ký tự, có cả chữ và số; tối đa 72 byte (docs/adr/0009). */
    public void check(String password) {
        int minLength = configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH);
        if (password.codePointCount(0, password.length()) < minLength) {
            throw new BusinessRuleViolationException("BR-TK-03",
                    "Mật khẩu phải có ít nhất " + minLength + " ký tự");
        }
        if (password.codePoints().noneMatch(Character::isLetter)
                || password.codePoints().noneMatch(Character::isDigit)) {
            throw new BusinessRuleViolationException("BR-TK-03", "Mật khẩu phải có cả chữ và số");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordConfig.BCRYPT_MAX_BYTES) {
            throw new BusinessRuleViolationException("BR-TK-03",
                    "Mật khẩu quá dài (tối đa " + PasswordConfig.BCRYPT_MAX_BYTES + " byte)");
        }
    }
}
