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

import com.petcare.module.appointment.service.AppointmentNoShowService;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.appointment.service.NoShowCutoff;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST05 — chuyển lịch hẹn {@code BOOKED} quá giờ sang {@code NO_SHOW} (BR-LH-08, docs/adr/0048; cơ chế job:
 * docs/adr/0007). Đọc id quá hạn theo lô (keyset {@code id}), mỗi lịch xử lý trong một transaction riêng ở
 * {@link AppointmentNoShowService}. Không {@code @Transactional} ở đây. Một lịch lỗi (ví dụ {@code BoardingQueryApi} chưa
 * cài — nợ D033) được log {@code NO_SHOW_FAILED} rồi bỏ qua; lượt sau thử lại vì điều kiện chỉ dựa trên trạng thái và
 * giờ hẹn. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(NoShowProperties.class)
public class NoShowJob {

    private final AppointmentNoShowService noShows;
    private final NoShowProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public NoShowJob(AppointmentNoShowService noShows, NoShowProperties properties, Clock clock) {
        this(noShows, properties, clock, System::nanoTime);
    }

    NoShowJob(AppointmentNoShowService noShows, NoShowProperties properties, Clock clock, LongSupplier ticker) {
        this.noShows = noShows;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.no-show.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        int marked = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            NoShowCutoff cutoff = noShows.cutoff(now);
            long lastId = 0;
            List<Long> ids;
            batches:
            do {
                ids = noShows.findOverdueIds(cutoff, lastId, properties.batchSize());
                for (Long id : ids) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        if (noShows.markNoShow(id, cutoff) == NoShowResult.MARKED) {
                            marked++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("NO_SHOW_FAILED appointmentId={} exception={}", id, e.getClass().getSimpleName(), e);
                    }
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("NO_SHOW marked={} skipped={} failed={} stoppedByBudget={} cutoff={} now={} durationMs={}", marked,
                    skipped, failed, stoppedByBudget, cutoff, now, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("NO_SHOW_FAILED markedSoFar={} now={} durationMs={}", marked, now, elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
