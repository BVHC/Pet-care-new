package com.petcare.module.shop.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.Result;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST10 — hủy đơn online {@code PENDING_PAYMENT} quá {@code payment_due_at} (BR-BH-12, docs/adr/0146; cơ chế job:
 * docs/adr/0007). Đọc theo lô (keyset theo hạn rồi id), mỗi đơn xử lý trong một transaction riêng ở
 * {@link OnlineOrderPaymentExpiryService}. Không {@code @Transactional} ở đây. Một đơn lỗi được log
 * {@code ONLINE_ORDER_PAYMENT_EXPIRY_FAILED} rồi bỏ qua; lượt sau thử lại. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OnlineOrderPaymentExpiryProperties.class)
public class OnlineOrderPaymentExpiryJob {

    private final OnlineOrderPaymentExpiryService expiry;
    private final OnlineOrderPaymentExpiryProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public OnlineOrderPaymentExpiryJob(OnlineOrderPaymentExpiryService expiry,
            OnlineOrderPaymentExpiryProperties properties, Clock clock) {
        this(expiry, properties, clock, System::nanoTime);
    }

    OnlineOrderPaymentExpiryJob(OnlineOrderPaymentExpiryService expiry, OnlineOrderPaymentExpiryProperties properties,
            Clock clock, LongSupplier ticker) {
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.online-order-payment-expiry.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        Instant now = null;
        try {
            now = Instant.now(clock);
            Instant afterDue = null;
            long afterId = 0;
            List<DueOrder> rows;
            batches:
            do {
                rows = expiry.findDue(now, afterDue, afterId, properties.batchSize());
                for (DueOrder row : rows) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        if (expiry.expire(row.id()) == Result.EXPIRED) {
                            expired++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("ONLINE_ORDER_PAYMENT_EXPIRY_FAILED onlineOrderId={} exception={}", row.id(),
                                e.getClass().getSimpleName(), e);
                    }
                    afterDue = row.paymentDueAt();
                    afterId = row.id();
                }
            } while (rows.size() == properties.batchSize());
            log.info("ONLINE_ORDER_PAYMENT_EXPIRY expired={} skipped={} failed={} stoppedByBudget={} now={} durationMs={}",
                    expired, skipped, failed, stoppedByBudget, now, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("ONLINE_ORDER_PAYMENT_EXPIRY_FAILED expiredSoFar={} now={} durationMs={}", expired, now,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
