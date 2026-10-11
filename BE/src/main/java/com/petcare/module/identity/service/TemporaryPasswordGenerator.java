package com.petcare.module.identity.service;

import java.security.SecureRandom;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.security.PasswordConfig;

/**
 * Mật khẩu ban đầu / mật khẩu tạm của nhân viên (BR-QT-02, 10; docs/adr/0029 mục 5). Hệ thống tự sinh, người tạo tài
 * khoản không thấy: mật khẩu gốc chỉ vào payload outbox {@code STAFF_TEMP_PASSWORD} (khóa {@code mat_khau_tam}, bị
 * xóa sau khi gửi), không vào log, response hay audit.
 * <p>
 * Gọi <b>ngoài</b> transaction (khuôn {@code OtpService.prepare}, docs/adr/0023): BCrypt không chạy khi đang giữ
 * connection hay khóa dòng. Mật khẩu luôn qua {@link PasswordPolicy} (BR-TK-03): độ dài
 * {@code max(12, password.min_length)} [CFG] (khoảng [CFG] 8–64 nên ≤ 64 ký tự ASCII, dưới 72 byte của BCrypt), có
 * ít nhất một chữ và một số, bỏ ký tự dễ nhầm {@code 0 O o 1 l I}. Không hết hạn: đặc tả không quy định, BR-TK-17
 * bắt đổi ở lần đăng nhập đầu (TBD trong ADR).
 */
@Component
public class TemporaryPasswordGenerator {

    static final int MIN_LENGTH = 12;
    static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    static final String DIGITS = "23456789";
    static final String ALPHABET = LETTERS + DIGITS;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Tên thành phần cố ý chứa {@code password}: nếu record lọt vào snapshot audit, {@code AuditRecorder} từ chối
     * ({@code SENSITIVE_WORDS} không có {@code mat_khau}, chỉ có {@code password}).
     */
    public record TemporaryPassword(String plainPassword, String passwordHash) {

        @Override
        public String toString() {
            return "TemporaryPassword[plainPassword=***, passwordHash=***]";
        }
    }

    private final SystemConfigApi configs;
    private final PasswordEncoder passwordEncoder;

    public TemporaryPasswordGenerator(SystemConfigApi configs, PasswordEncoder passwordEncoder) {
        this.configs = configs;
        this.passwordEncoder = passwordEncoder;
    }

    public TemporaryPassword prepare() {
        String plain = newPassword(Math.max(MIN_LENGTH, configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)));
        return new TemporaryPassword(plain, passwordEncoder.encode(plain));
    }

    static String newPassword(int length) {
        if (length < 2 || length > PasswordConfig.BCRYPT_MAX_BYTES) {
            throw new IllegalArgumentException("Độ dài mật khẩu tạm không hợp lệ: " + length);
        }
        char[] chars = new char[length];
        chars[0] = LETTERS.charAt(RANDOM.nextInt(LETTERS.length()));
        chars[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
        for (int i = 2; i < length; i++) {
            chars[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }
        for (int i = length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char swap = chars[i];
            chars[i] = chars[j];
            chars[j] = swap;
        }
        return new String(chars);
    }
}
