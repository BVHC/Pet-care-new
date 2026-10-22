package com.petcare.module.sales;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.petcare.support.MutableClock;

/**
 * {@code Clock} dịch được cho IT thu ngân (docs/adr/0064): mọi IT dùng {@code @Import(CashierClockConfig.class)} nên
 * chung một context. Mỗi test tự đặt giờ trong {@code @BeforeEach}; năm 2005 để dữ liệu không đụng IT của tính năng
 * khác (CLAUDE.md, mục Testing).
 */
@TestConfiguration(proxyBeanMethods = false)
public class CashierClockConfig {

    /** 2005-06-15 (thứ tư) 10:00 giờ Việt Nam. */
    public static final Instant START = Instant.parse("2005-06-15T03:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
