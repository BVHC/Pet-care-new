package com.petcare.module.care.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.DeliveryLane;
import com.petcare.platform.config.TimeConfig;

/**
 * ST20 luồng NORMAL (docs/adr/0012): mọi email không thuộc luồng HIGH (nhắc lịch, hủy do phòng khám, cảnh báo…).
 * Cơ chế job: docs/adr/0007. Không {@code @Transactional}.
 */
@Component
public class NotificationOutboxJob {

    private final NotificationOutboxRunner runner;

    public NotificationOutboxJob(NotificationOutboxRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${app.jobs.notification-outbox.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        runner.run(DeliveryLane.NORMAL);
    }
}
