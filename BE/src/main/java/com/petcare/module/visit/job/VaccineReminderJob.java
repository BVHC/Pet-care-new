package com.petcare.module.visit.job;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.VaccineReminderService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST04 — nhắc tái chủng (BR-TB-01, BR-TB-02; docs/adr/0082; cơ chế job: docs/adr/0007; khuôn
 * {@code AppointmentReminderJob}). Đọc id mũi đến hạn theo lô (keyset theo ngày đến hạn rồi id), mỗi mũi xử lý trong một
 * transaction riêng ở {@link VaccineReminderService}. Không {@code @Transactional} ở đây. Một mũi lỗi được log
 * {@code VACCINE_REMINDER_FAILED} rồi bỏ qua; lượt sau thử lại vì điều kiện chỉ dựa trên {@code due_reminded_at},
 * {@code superseded_at} và ngày đến hạn. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(VaccineReminderProperties.class)
public class VaccineReminderJob {

    private final VaccineReminderService reminders;
    private final VaccineReminderProperties properties;
    private final LongSupplier ticker;

    @Autowired
    public VaccineReminderJob(VaccineReminderService reminders, VaccineReminderProperties properties) {
        this(reminders, properties, System::nanoTime);
    }

    VaccineReminderJob(VaccineReminderService reminders, VaccineReminderProperties properties, LongSupplier ticker) {
        this.reminders = reminders;
        this.properties = properties;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.vaccine-reminder.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int reminded = 0;
        int tasks = 0;
        int notSent = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            DueDateWindow window = reminders.window();
            long lastId = 0;
            List<Long> ids;
            batches:
            do {
                ids = reminders.findDueIds(window, lastId, properties.batchSize());
                for (Long id : ids) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        switch (reminders.remind(id, window)) {
                            case REMINDED -> reminded++;
                            case TASK_CREATED -> tasks++;
                            case NOT_SENT -> notSent++;
                            case SKIPPED -> skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("VACCINE_REMINDER_FAILED vaccinationId={} exception={}", id,
                                e.getClass().getSimpleName(), e);
                    }
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("VACCINE_REMINDER reminded={} tasks={} notSent={} skipped={} failed={} stoppedByBudget={} "
                    + "window={} durationMs={}", reminded, tasks, notSent, skipped, failed, stoppedByBudget, window,
                    elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("VACCINE_REMINDER_FAILED remindedSoFar={} durationMs={}", reminded + tasks, elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
