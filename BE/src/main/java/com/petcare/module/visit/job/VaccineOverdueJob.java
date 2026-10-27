package com.petcare.module.visit.job;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.visit.service.VaccineOverdueService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST18 — sinh Care Task quá hạn tái chủng (BR-TB-04; docs/adr/0083; cơ chế job: docs/adr/0007; khuôn
 * {@code VaccineReminderJob}). Đọc id mũi quá hạn theo lô (keyset theo ngày đến hạn rồi id), mỗi mũi xử lý trong một
 * transaction riêng ở {@link VaccineOverdueService}. Không {@code @Transactional} ở đây. Một mũi lỗi được log
 * {@code VACCINE_OVERDUE_FAILED} rồi bỏ qua (không chặn mũi sau); lượt sau thử lại vì điều kiện chỉ dựa trên
 * {@code overdue_handled_at}, {@code superseded_at} và ngày đến hạn. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(VaccineOverdueProperties.class)
public class VaccineOverdueJob {

    private final VaccineOverdueService overdue;
    private final VaccineOverdueProperties properties;
    private final LongSupplier ticker;

    @Autowired
    public VaccineOverdueJob(VaccineOverdueService overdue, VaccineOverdueProperties properties) {
        this(overdue, properties, System::nanoTime);
    }

    VaccineOverdueJob(VaccineOverdueService overdue, VaccineOverdueProperties properties, LongSupplier ticker) {
        this.overdue = overdue;
        this.properties = properties;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.vaccine-overdue.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int created = 0;
        int notCreated = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            LocalDate cutoff = overdue.cutoff();
            long lastId = 0;
            List<Long> ids;
            batches:
            do {
                ids = overdue.findOverdueIds(cutoff, lastId, properties.batchSize());
                for (Long id : ids) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        switch (overdue.raise(id, cutoff)) {
                            case TASK_CREATED -> created++;
                            case NOT_SENT -> notCreated++;
                            case SKIPPED -> skipped++;
                            case REMINDED -> throw new IllegalStateException("ST18 không nhắc, chỉ sinh task");
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("VACCINE_OVERDUE_FAILED vaccinationId={} exception={}", id,
                                e.getClass().getSimpleName(), e);
                    }
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("VACCINE_OVERDUE created={} notCreated={} skipped={} failed={} stoppedByBudget={} cutoff={} "
                    + "durationMs={}", created, notCreated, skipped, failed, stoppedByBudget, cutoff, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("VACCINE_OVERDUE_FAILED createdSoFar={} durationMs={}", created, elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
