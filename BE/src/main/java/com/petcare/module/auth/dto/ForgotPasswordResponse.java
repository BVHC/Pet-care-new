package com.petcare.module.auth.dto;

/**
 * Luôn trả {@code sent=true} kể cả khi email không tồn tại — tránh lộ email nào
 * đã đăng ký (account enumeration).
 */
public record ForgotPasswordResponse(boolean sent) {
}
