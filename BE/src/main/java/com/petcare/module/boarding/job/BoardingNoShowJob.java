package com.petcare.module.boarding.job;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.service.BoardingNoShowService;
import com.petcare.module.boarding.service.BoardingNoShowService.DueBooking;
import com.petcare.module.boarding.service.BoardingNoShowService.NoShowResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;
import com.petcare.platform.metrics.JobMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * ST05 — chuyển đặt chỗ lưu trú {@code BOOKED} chưa được nhận khi hết giờ làm việc của ngày nhận sang {@code NO_SHOW}
 * (BR-LT-06, docs/adr/0065; cơ chế job: docs/adr/0007). Đọc theo lô (keyset theo ngày nhận rồi id), mỗi đặt chỗ xử lý
 * trong một transaction riêng ở {@link BoardingNoShowService}. Không {@code @Transactional} ở đây. Một đặt chỗ lỗi được
 * log {@code BOARDING_NO_SHOW_FAILED} rồi bỏ qua; lượt sau thử lại. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(BoardingNoShowProperties.class)
public class BoardingNoShowJob {

    private final BoardingNoShowService noShows;
    private final BoardingNoShowProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    @Autowired
    public BoardingNoShowJob(BoardingNoShowService noShows, BoardingNoShowProperties properties, Clock clock) {
        this(noShows, properties, clock, System::nanoTime);
    }

    BoardingNoShowJob(BoardingNoShowService noShows, BoardingNoShowProperties properties, Clock clock,
            LongSupplier ticker) {
        this.noShows = noShows;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${app.jobs.boarding-no-show.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int marked = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        LocalDate today = null;
        try {
            today = LocalDate.now(clock);
            LocalDate afterDate = null;
            long afterId = 0;
            List<DueBooking> rows;
            batches:
            do {
                rows = noShows.findDue(today, afterDate, afterId, properties.batchSize());
                for (DueBooking row : rows) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        if (noShows.markNoShow(row.id()) == NoShowResult.MARKED) {
                            marked++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("BOARDING_NO_SHOW_FAILED bookingId={} exception={}", row.id(),
                                e.getClass().getSimpleName(), e);
                    }
                    afterDate = row.checkInDate();
                    afterId = row.id();
                }
            } while (rows.size() == properties.batchSize());
            log.info("BOARDING_NO_SHOW marked={} skipped={} failed={} stoppedByBudget={} today={} durationMs={}", marked,
                    skipped, failed, stoppedByBudget, today, elapsedMs(started));
            jobMetrics.record("boarding_no_show", JobMetrics.Outcome.finished(stoppedByBudget),
                    Map.of("marked", marked, "skipped", skipped, "failed", failed));
        } catch (RuntimeException e) {
            log.error("BOARDING_NO_SHOW_FAILED markedSoFar={} today={} durationMs={}", marked, today,
                    elapsedMs(started), e);
            jobMetrics.record("boarding_no_show", JobMetrics.Outcome.FAILED,
                    Map.of("marked", marked, "skipped", skipped, "failed", failed));
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
