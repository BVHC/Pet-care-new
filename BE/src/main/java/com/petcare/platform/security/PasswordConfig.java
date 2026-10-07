package com.petcare.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Băm mật khẩu và mã OTP bằng BCrypt (docs/adr/0009). BCrypt chỉ nhận tối đa 72 byte: bản Spring Security đang dùng
 * ném {@code IllegalArgumentException} khi dài hơn, nên service kiểm độ dài trước và trả lỗi nghiệp vụ (BR-TK-03).
 */
@Configuration
public class PasswordConfig {

    /** Giới hạn đầu vào của BCrypt, tính theo byte UTF-8. */
    public static final int BCRYPT_MAX_BYTES = 72;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
