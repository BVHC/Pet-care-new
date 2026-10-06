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

    /** Dạng chuỗi cho chỗ cần hằng số lúc biên dịch, ví dụ {@code @Scheduled(zone = ...)} (docs/adr/0007). */
    public static final String BUSINESS_ZONE_ID = "Asia/Ho_Chi_Minh";

    public static final ZoneId BUSINESS_ZONE = ZoneId.of(BUSINESS_ZONE_ID);

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
