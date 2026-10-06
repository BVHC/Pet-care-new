package com.petcare.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật {@code @Scheduled} cho job định kỳ (docs/adr/0007). Scheduler mặc định của Spring Boot có 1 thread nên các job
 * chạy tuần tự và một job không chồng lên chính nó. Giả định chạy 1 instance; job đặt ở {@code module/<m>/job/},
 * cron lấy từ {@code app.jobs.<job>.cron} theo {@link TimeConfig#BUSINESS_ZONE_ID}, {@code "-"} để tắt.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
