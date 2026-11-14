package com.petcare.module.content.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.service.FeedbackCleanupService;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * ST24 (BR-DG-05, docs/adr/0122, 0131): mốc cắt đúng dấu và đúng khóa [CFG], chốt chặn hạn lưu giữ, vòng lặp theo lô,
 * nghỉ giữa các lô, ngân sách, log, MDC và lịch của {@link FeedbackCleanupJob}. Xóa là không hoàn tác nên mọi nhánh
 * "không xóa" đều được kiểm là không gọi service.
 */
class FeedbackCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-10T20:30:31Z");
    private static final int BATCH = 500;
    private static final Duration GAP = Duration.ofMillis(50);

    private final FeedbackCleanupService cleanup = mock(FeedbackCleanupService.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final AtomicLong nanos = new AtomicLong();
    private final List<Duration> pauses = new ArrayList<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger(FeedbackCleanupJob.class);
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
        when(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).thenReturn(30);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        MDC.clear();
        Thread.interrupted();
    }

    private FeedbackCleanupJob job() {
        return job(GAP);
    }

    private FeedbackCleanupJob job(Duration gap) {
        return new FeedbackCleanupJob(cleanup,
                new FeedbackCleanupProperties("31 30 3 * * *", BATCH, gap, Duration.ofSeconds(60)), config,
                Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), nanos::get, pauses::add);
    }

    // ---------------------------------------------------------------- mốc cắt

    /** Đúng dấu (trừ, không cộng), đúng đơn vị (ngày), đúng khóa (không nhầm {@code feedback.max_per_day} = 5). */
    @Test
    void cutoffIsNowMinusRetentionDaysFromTheRetentionKeyOnly() {
        job().run();

        InOrder order = inOrder(config, cleanup);
        order.verify(config).getInt(ConfigKey.FEEDBACK_RETENTION_DAYS);
        order.verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(30)), BATCH);
        verifyNoMoreInteractions(config, cleanup);
    }

    @Test
    void retentionIsReadAtEveryRun() {
        when(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).thenReturn(30, 10);

        FeedbackCleanupJob job = job();
        job.run();
        job.run();

        verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(30)), BATCH);
        verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(10)), BATCH);
    }

    // ---------------------------------------------------------------- chốt chặn hạn lưu giữ

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 6, 366, 3650, Integer.MAX_VALUE})
    void retentionOutsideTheAllowedRangeDeletesNothing(int days) {
        when(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).thenReturn(days);

        assertThatCode(job()::run).doesNotThrowAnyException();

        verify(cleanup, never()).deleteExpiredBatch(any(), anyInt());
        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("FEEDBACK_CLEANUP_SKIPPED retentionDays=" + days + " allowed=7..365 ");
        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 365})
    void retentionOnTheBoundsOfTheAllowedRangeStillRuns(int days) {
        when(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).thenReturn(days);

        job().run();

        verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(days)), BATCH);
    }

    // ---------------------------------------------------------------- vòng lặp theo lô

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsOnPartialBatch() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH, BATCH, 3);

        job().run();

        verify(cleanup, times(3)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("FEEDBACK_CLEANUP deleted=1003 batches=3 ").contains("budgetHit=false");
    }

    @Test
    void nothingToDeleteIsOneBatchAndOneInfoLine() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(0);

        job().run();

        verify(cleanup, times(1)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString().startsWith("FEEDBACK_CLEANUP deleted=0 batches=1 ");
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpentAndTheNextRunContinues() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(40).toNanos());
            return BATCH;
        });

        job().run();

        verify(cleanup, times(2)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("FEEDBACK_CLEANUP deleted=1000 batches=2 ").contains("budgetHit=true");
    }

    // ---------------------------------------------------------------- nghỉ giữa các lô

    /** Nghỉ sau mỗi lô đầy, không nghỉ sau lô cuối (thiếu): không làm chậm lượt chỉ có một lô. */
    @Test
    void pausesAfterEveryFullBatchButNotAfterTheLastOne() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH, BATCH, 3);

        job().run();

        assertThat(pauses).containsExactly(GAP, GAP);
    }

    @Test
    void aSingleShortBatchNeverPauses() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(7);

        job().run();

        assertThat(pauses).isEmpty();
    }

    @Test
    void zeroGapNeverPauses() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH, 1);

        job(Duration.ZERO).run();

        verify(cleanup, times(2)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(pauses).isEmpty();
    }

    /** Bị ngắt khi nghỉ: dừng lượt, giữ cờ ngắt cho luồng scheduler, không ném; lô đã xóa đã commit. */
    @Test
    void interruptWhileResting_stopsTheRunKeepsTheFlagAndDoesNotThrow() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH);
        FeedbackCleanupJob interrupted = new FeedbackCleanupJob(cleanup,
                new FeedbackCleanupProperties("31 30 3 * * *", BATCH, GAP, Duration.ofSeconds(60)), config,
                Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), nanos::get, duration -> {
                    throw new InterruptedException("stop");
                });

        assertThatCode(interrupted::run).doesNotThrowAnyException();

        verify(cleanup, times(1)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(messages(Level.WARN)).containsExactly("FEEDBACK_CLEANUP_INTERRUPTED");
        assertThat(messages(Level.INFO)).singleElement().asString().startsWith("FEEDBACK_CLEANUP deleted=500 batches=1 ");
    }

    // ---------------------------------------------------------------- lỗi, log, MDC

    /** Lỗi ở một lô: không ném ra scheduler, log kèm số dòng đã xóa ở các lô trước (đã commit riêng). */
    @Test
    void failureIsLoggedWithCountsSoFarNotThrown() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH).thenThrow(new IllegalStateException("db"));

        assertThatCode(job()::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("FEEDBACK_CLEANUP_FAILED deletedSoFar=500 batches=1 ");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void failureReadingTheConfigDeletesNothingAndDoesNotThrow() {
        when(config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS)).thenThrow(new IllegalArgumentException("config"));

        assertThatCode(job()::run).doesNotThrowAnyException();

        verify(cleanup, never()).deleteExpiredBatch(any(), anyInt());
        assertThat(messages(Level.ERROR)).singleElement().asString().startsWith("FEEDBACK_CLEANUP_FAILED deletedSoFar=0 ");
    }

    @Test
    void eachRunHasItsOwnTraceIdThatIsRemovedAfterwards() {
        List<String> seen = new ArrayList<>();
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenAnswer(invocation -> {
            seen.add(MDC.get(TraceContext.MDC_KEY));
            return 0;
        });

        FeedbackCleanupJob job = job();
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

        job().run();

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    // ---------------------------------------------------------------- lịch và giao dịch

    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Scheduled scheduled = FeedbackCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.feedback-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }

    /** Mỗi lô một transaction ở service; {@code @Transactional} ở job sẽ gộp mọi lô và rollback cả những lô đã xóa. */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = FeedbackCleanupJob.class.getMethod("run");

        assertThat(FeedbackCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(FeedbackCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "31 30 3 * * *": 03:30:31 giờ Việt Nam, giây 31 lệch ST20 (mỗi 5 / 10 giây) và job {@code *}/5. */
    @Test
    void defaultCronFiresAtThreeThirtyThirtyOneVietnamTime() {
        ZonedDateTime from = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime next = CronExpression.parse("31 30 3 * * *").next(from);

        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 11, 3, 30, 31, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(next.getSecond() % 5).isNotZero();
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
