package com.petcare.module.identity.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hash BCrypt giả cùng cost (docs/adr/0009), tạo một lần lúc khởi động. Nhánh không có hash thật để so vẫn chạy đúng
 * một lần BCrypt, nên thời gian phản hồi không cho biết email có tài khoản hay không (BR-TK-10; docs/adr/0023, như
 * {@code LoginService} — docs/adr/0019 mục 5). Kết quả luôn {@code false}.
 */
@Component
public class BcryptDecoy {

    private final PasswordEncoder passwordEncoder;
    private final String hash;

    public BcryptDecoy(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.hash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Một lần {@code matches} với hash giả; luôn {@code false}. */
    public boolean matches(String raw) {
        passwordEncoder.matches(raw, hash);
        return false;
    }
}
