package com.petcare.module.order.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RULE-14-04 — ProcessOrderTimeout, mirror {@code RefreshTokenCleanupJob}/{@code CaregiverExpiryJob}:
 * {@code @Scheduled} đơn giản, không distributed lock (an toàn với đúng 1 backend instance hiện
 * tại — docker-compose.yml). Chạy mỗi phút (khác {@code CaregiverExpiryJob} 15 phút/lần) vì Hold
 * TTL chỉ 15 phút — cần độ trễ phát hiện nhỏ hơn nhiều so với TTL để không giữ tồn kho ảo quá lâu.
 */
@Component
@ConditionalOnProperty(prefix = "app.order-timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessOrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderTimeoutJob.class);

    private final OrderTimeoutService orderTimeoutService;

    public ProcessOrderTimeoutJob(OrderTimeoutService orderTimeoutService) {
        this.orderTimeoutService = orderTimeoutService;
    }

    @Scheduled(cron = "${app.order-timeout.cron:0 * * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void expireStaleOrders() {
        int processed = orderTimeoutService.processExpiredOrders();
        if (processed > 0) {
            log.info("ProcessOrderTimeoutJob: expired {} order(s)", processed);
        }
    }
}
