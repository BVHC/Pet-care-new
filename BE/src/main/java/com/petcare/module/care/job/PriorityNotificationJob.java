package com.petcare.module.care.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.DeliveryLane;
import com.petcare.platform.config.TimeConfig;

/**
 * ST20 luồng HIGH (docs/adr/0012): email có người đang chờ (OTP, mật khẩu tạm), quét dày và tách khỏi thư hàng loạt để
 * OTP không xếp hàng sau nhắc lịch. Cơ chế job: docs/adr/0007. Không {@code @Transactional}.
 */
@Component
public class PriorityNotificationJob {

    private final NotificationOutboxRunner runner;

    public PriorityNotificationJob(NotificationOutboxRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${app.jobs.notification-outbox.priority-cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        runner.run(DeliveryLane.HIGH);
    }
}
