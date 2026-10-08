package com.petcare.module.care.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.DeliveryLane;
import com.petcare.platform.config.TimeConfig;

/**
 * ST20 luồng IN_APP (docs/adr/0014): giao dòng outbox kênh {@code IN_APP} vào bảng {@code notifications}. Job riêng để
 * không phụ thuộc SMTP (luồng email dừng hoặc treo khi SMTP lỗi) và không chiếm ngân sách của OTP. Cơ chế job:
 * docs/adr/0007. Không {@code @Transactional}.
 */
@Component
public class InAppNotificationJob {

    private final NotificationOutboxRunner runner;

    public InAppNotificationJob(NotificationOutboxRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${app.jobs.notification-outbox.in-app-cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        runner.run(DeliveryLane.IN_APP);
    }
}
