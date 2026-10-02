package com.petcare.platform.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Thời gian của hệ thống không phụ thuộc timezone của JVM (test chạy Asia/Ho_Chi_Minh, container chạy UTC).
 * Thời điểm lưu UTC ({@code Instant.now(clock)}, TIMESTAMPTZ); ngày nghiệp vụ hiểu theo giờ Việt Nam
 * ({@code LocalDate.now(clock)}) — docs/05-erd.md §0, BR-BC-01. Test thay bean bằng {@code Clock.fixed(...)}.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
