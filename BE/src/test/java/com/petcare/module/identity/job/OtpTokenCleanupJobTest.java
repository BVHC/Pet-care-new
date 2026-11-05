package com.petcare.module.identity.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.service.OtpTokenCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Mốc cắt, vòng lặp theo lô, ngân sách thời gian, lịch chạy, log và MDC của job dọn OTP (docs/adr/0007, 0111). */
class OtpTokenCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-05T20:20:17Z");
    private static final int BATCH = 1000;

    private final OtpTokenCleanupService cleanup = mock(OtpTokenCleanupService.class);
    private final AtomicLong nanos = new AtomicLong();
    private final OtpTokenCleanupJob job = new OtpTokenCleanupJob(cleanup,
            new OtpTokenCleanupProperties("17 20 3 * * *", 1, BATCH, Duration.ofSeconds(60)),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), nanos::get);

    private final Logger logger = (Logger) LoggerFactory.getLogger(OtpTokenCleanupJob.class);
    /** Chụp MDC ngay lúc ghi log: logback đọc MDC lười, sau khi job gỡ traceId thì không còn để kiểm. */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void attachAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    @Test
    void cutoffIsNowMinusRetention() {
        job.run();

        verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(1)), BATCH);
    }

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsOnPartialBatch() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH, BATCH, 3);

        job.run();

        verify(cleanup, times(3)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("OTP_TOKEN_CLEANUP deleted=2003 ").contains("budgetHit=false");
    }

    /** Hết ngân sách giữa chừng: dừng, không xóa tiếp; phần còn lại dọn lượt sau. */
    @Test
    void stopsWhenTimeBudgetIsSpent() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(40).toNanos());
            return BATCH;
        });

        job.run();

        verify(cleanup, times(2)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("OTP_TOKEN_CLEANUP deleted=2000 ").contains("budgetHit=true");
    }

    /** Lỗi ở một lô: không ném ra scheduler, log kèm số dòng đã xóa ở các lô trước (đã commit riêng). */
    @Test
    void failureIsLoggedWithCountSoFarNotThrown() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH).thenThrow(new IllegalStateException("db"));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("OTP_TOKEN_CLEANUP_FAILED deletedSoFar=1000 ");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void eachRunHasItsOwnTraceIdThatIsRemovedAfterwards() {
        List<String> seen = new ArrayList<>();
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenAnswer(invocation -> {
            seen.add(MDC.get(TraceContext.MDC_KEY));
            return 0;
        });

        job.run();
        job.run();

        assertThat(seen).hasSize(2).allSatisfy(id -> assertThat(id).startsWith("job-"));
        assertThat(seen.get(0)).isNotEqualTo(seen.get(1));
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getMDCPropertyMap().get(TraceContext.MDC_KEY)).startsWith("job-"));
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void mdcRemovedEvenWhenBatchFails() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenThrow(new IllegalStateException("db"));

        job.run();

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Scheduled scheduled = OtpTokenCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.otp-token-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }

    /** Mỗi lô một transaction ở service (convention 07 §7.4); {@code @Transactional} ở job sẽ gộp mọi lô. */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = OtpTokenCleanupJob.class.getMethod("run");

        assertThat(OtpTokenCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(OtpTokenCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "17 20 3 * * *": 03:20:17 giờ Việt Nam, giữa dọn phiên (03:00) và dọn outbox (03:40), giây lệch ST20. */
    @Test
    void defaultCronFiresAtThreeTwentySeventeenVietnamTime() {
        ZonedDateTime from = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime next = CronExpression.parse("17 20 3 * * *").next(from);

        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 6, 3, 20, 17, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(next.getSecond() % 5).isNotZero();
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
