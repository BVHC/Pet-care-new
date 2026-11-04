package com.petcare.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.rate-limit.*}: giới hạn tần suất theo IP cho endpoint public của identity (docs/adr/0100, nợ D011).
 * Không phải tham số [CFG]: ngoài đặc tả, cấu hình như lịch job bằng biến môi trường {@code RATE_LIMIT_*}. Giá trị
 * mặc định nằm ở {@code application.yml}; profile {@code test} tắt hẳn.
 *
 * @param enabled        tắt thì không có bean bộ đếm, không gắn filter, không gọi Redis
 * @param login          {@code POST /api/auth/login}
 * @param register       {@code POST /api/auth/register}, {@code /register/verify}, {@code /register/resend-otp}
 * @param password       {@code POST /api/auth/password/forgot}, {@code /password/reset}
 * @param breakerSeconds Redis lỗi thì bỏ qua Redis (cho qua) trong bấy nhiêu giây rồi thử lại
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @Valid Rule login,
        @NotNull @Valid Rule register,
        @NotNull @Valid Rule password,
        @DefaultValue("10") @Min(value = 1, message = "breaker-seconds phải từ 1 trở lên") int breakerSeconds) {

    /** Tối đa {@code limit} request trong mỗi cửa sổ {@code windowSeconds} giây cho một IP. */
    public record Rule(
            @Min(value = 1, message = "limit phải từ 1 trở lên") int limit,
            @Min(value = 1, message = "window-seconds phải từ 1 trở lên") int windowSeconds) {
    }
}
