package com.petcare.module.returns.job;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.returns.repository.GatewayRefundDao;
import com.petcare.module.returns.service.GatewayRefundService;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Quét ST21 theo lô giới hạn, mỗi refund một transaction độc lập. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "FAKE", matchIfMissing = true)
@RequiredArgsConstructor
public class GatewayRefundJob {

    private static final int BATCH_SIZE = 20;
    private static final long BUDGET_NANOS = Duration.ofSeconds(10).toNanos();

    private final GatewayRefundDao refunds;
    private final GatewayRefundService service;
    private final Clock clock;

    @Scheduled(cron = "${app.jobs.gateway-refund.cron:-}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        long started = System.nanoTime();
        for (long refundId : refunds.findDue(clock.instant(), BATCH_SIZE)) {
            if (System.nanoTime() - started >= BUDGET_NANOS) {
                break;
            }
            try {
                service.process(refundId);
            } catch (RuntimeException ex) {
                log.error("GATEWAY_REFUND_FAILED refundId={} exception={}", refundId,
                        ex.getClass().getSimpleName(), ex);
            }
        }
    }
}
