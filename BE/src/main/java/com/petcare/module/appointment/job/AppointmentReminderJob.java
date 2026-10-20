package com.petcare.module.appointment.job;

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

import com.petcare.module.appointment.service.AppointmentReminderService;
import com.petcare.module.appointment.service.ReminderWindow;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST03 — nhắc lịch hẹn trước giờ hẹn (BR-LH-12, docs/adr/0051; cơ chế job: docs/adr/0007; khuôn {@code NoShowJob}). Đọc id
 * đến hạn theo lô (keyset theo khung giờ rồi id), mỗi lịch xử lý trong một transaction riêng ở
 * {@link AppointmentReminderService}. Không {@code @Transactional} ở đây. Một lịch lỗi được log
 * {@code APPOINTMENT_REMINDER_FAILED} rồi bỏ qua; lượt sau thử lại vì điều kiện chỉ dựa trên trạng thái,
 * {@code reminded_at} và giờ hẹn. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AppointmentReminderProperties.class)
public class AppointmentReminderJob {

    private final AppointmentReminderService reminders;
    private final AppointmentReminderProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public AppointmentReminderJob(AppointmentReminderService reminders, AppointmentReminderProperties properties,
            Clock clock) {
        this(reminders, properties, clock, System::nanoTime);
    }

    AppointmentReminderJob(AppointmentReminderService reminders, AppointmentReminderProperties properties, Clock clock,
            LongSupplier ticker) {
        this.reminders = reminders;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.appointment-reminder.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        int reminded = 0;
        int notSent = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            ReminderWindow window = reminders.window(now);
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
                            case NOT_SENT -> notSent++;
                            case SKIPPED -> skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("APPOINTMENT_REMINDER_FAILED appointmentId={} exception={}", id,
                                e.getClass().getSimpleName(), e);
                    }
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("APPOINTMENT_REMINDER reminded={} notSent={} skipped={} failed={} stoppedByBudget={} window={} "
                    + "durationMs={}", reminded, notSent, skipped, failed, stoppedByBudget, window, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("APPOINTMENT_REMINDER_FAILED remindedSoFar={} now={} durationMs={}", reminded, now,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
