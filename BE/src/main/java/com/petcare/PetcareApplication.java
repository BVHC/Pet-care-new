package com.petcare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Không dùng user/mật khẩu sinh tự động của Spring Security; xác thực sẽ do module TK cung cấp.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PetcareApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetcareApplication.class, args);
    }
}
