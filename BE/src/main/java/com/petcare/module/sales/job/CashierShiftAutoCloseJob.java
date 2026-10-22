package com.petcare.module.sales.job;

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

import com.petcare.module.sales.service.CashierShiftAutoCloseService;
import com.petcare.module.sales.service.CashierShiftAutoCloseService.Result;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST13 — tự chốt ca thu ngân chưa chốt quá hạn (BR-TG-05, docs/adr/0064; cơ chế job: docs/adr/0007). Đọc id các ca
 * {@code OPEN} theo lô (keyset theo id), mỗi ca xử lý trong một transaction riêng ở {@link CashierShiftAutoCloseService}.
 * Không {@code @Transactional} ở đây. Một ca lỗi được log {@code CASHIER_SHIFT_AUTO_CLOSE_FAILED} rồi bỏ qua; lượt sau
 * thử lại vì điều kiện chỉ dựa trên trạng thái và giờ. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(CashierShiftAutoCloseProperties.class)
public class CashierShiftAutoCloseJob {

    private final CashierShiftAutoCloseService shifts;
    private final CashierShiftAutoCloseProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public CashierShiftAutoCloseJob(CashierShiftAutoCloseService shifts, CashierShiftAutoCloseProperties properties,
            Clock clock) {
        this(shifts, properties, clock, System::nanoTime);
    }

    CashierShiftAutoCloseJob(CashierShiftAutoCloseService shifts, CashierShiftAutoCloseProperties properties,
            Clock clock, LongSupplier ticker) {
        this.shifts = shifts;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.cashier-shift-auto-close.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        int closed = 0;
        int notDue = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            long lastId = 0;
            List<Long> ids;
            batches:
            do {
                ids = shifts.findOpenIds(lastId, properties.batchSize());
                for (Long id : ids) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        Result result = shifts.autoCloseCashierShift(id, now);
                        switch (result) {
                            case CLOSED -> closed++;
                            case NOT_DUE -> notDue++;
                            case SKIPPED, NO_DEADLINE -> skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("CASHIER_SHIFT_AUTO_CLOSE_FAILED shiftId={} exception={}", id,
                                e.getClass().getSimpleName(), e);
                    }
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("CASHIER_SHIFT_AUTO_CLOSE closed={} notDue={} skipped={} failed={} stoppedByBudget={} now={} "
                    + "durationMs={}", closed, notDue, skipped, failed, stoppedByBudget, now, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("CASHIER_SHIFT_AUTO_CLOSE_FAILED closedSoFar={} now={} durationMs={}", closed, now,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
