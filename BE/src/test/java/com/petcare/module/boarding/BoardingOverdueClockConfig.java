package com.petcare.module.boarding;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.petcare.support.MutableClock;

/**
 * Đồng hồ cho IT ST15: era năm 1988, cũ hơn mọi era khác vì lượt theo dõi quét mọi đặt chỗ {@code OVERDUE} không có cận
 * dưới (CLAUDE.md, Isolation between ITs). {@link #START} = 1988-06-10 (thứ sáu) 10:00 giờ Việt Nam.
 */
@TestConfiguration
public class BoardingOverdueClockConfig {

    public static final Instant START = Instant.parse("1988-06-10T03:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
