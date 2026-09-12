package com.petcare.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật Spring @Scheduled cho toàn ứng dụng. Job scheduled đầu tiên trong repo:
 * RefreshTokenCleanupJob (xem docs/adr/0003-refresh-token-cleanup-job.md).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
