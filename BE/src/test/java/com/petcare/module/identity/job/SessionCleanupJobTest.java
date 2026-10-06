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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.service.SessionCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Vòng lặp theo lô, mốc cắt, lịch chạy, log và MDC của job dọn phiên (docs/adr/0007, 0008). */
class SessionCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
    private static final int BATCH = 1000;

    private final SessionCleanupService cleanup = mock(SessionCleanupService.class);
    private final SessionCleanupJob job = new SessionCleanupJob(cleanup,
            new SessionCleanupProperties("0 0 3 * * *", 30, BATCH), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private final Logger logger = (Logger) LoggerFactory.getLogger(SessionCleanupJob.class);
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
    void cutoffIsNowFromClockMinusRetentionDays() {
        job.run();

        verify(cleanup).deleteExpiredBatch(NOW.minus(Duration.ofDays(30)), BATCH);
    }

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsOnPartialBatch() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH, BATCH, 3);

        job.run();

        verify(cleanup, times(3)).deleteExpiredBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString().startsWith("SESSION_CLEANUP deleted=2003 ");
    }

    @Test
    void emptyFirstBatchMeansOneCall() {
        job.run();

        verify(cleanup, times(1)).deleteExpiredBatch(any(), anyInt());
        assertThat(messages(Level.INFO)).singleElement().asString().startsWith("SESSION_CLEANUP deleted=0 ");
    }

    /** Lỗi ở một lô: không ném ra scheduler, log kèm số dòng đã xóa ở các lô trước (đã commit riêng). */
    @Test
    void failureIsLoggedNotThrown() {
        when(cleanup.deleteExpiredBatch(any(), anyInt())).thenReturn(BATCH).thenThrow(new IllegalStateException("db"));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("SESSION_CLEANUP_FAILED deletedSoFar=1000 ");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    /** Mỗi lượt chạy có traceId riêng trong log, và gỡ khỏi MDC sau khi xong (thread scheduler dùng lại). */
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

    /** Cron lấy từ property và chạy theo giờ Việt Nam; nếu quên zone, scheduler dùng timezone JVM (container: UTC). */
    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Method run = SessionCleanupJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.session-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID).isEqualTo("Asia/Ho_Chi_Minh");
    }

    /**
     * Mỗi lô một transaction ở service (convention 07 §7.4). {@code @Transactional} trên job sẽ gộp mọi lô thành một,
     * lô lỗi rollback cả các lô trước. {@code SessionCleanupIT.failedBatchKeepsEarlierBatchesDeleted} dựng job bằng
     * tay (không qua proxy) nên không thấy annotation này; test này chặn phần đó.
     */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = SessionCleanupJob.class.getMethod("run");

        assertThat(SessionCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(SessionCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "0 0 3 * * *": 03:00 giờ Việt Nam hằng ngày. */
    @Test
    void defaultCronFiresAtThreeAmVietnamTime() {
        ZonedDateTime from = ZonedDateTime.of(2026, 10, 6, 10, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime next = CronExpression.parse("0 0 3 * * *").next(from);

        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 10, 7, 3, 0, 0, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-10-06T20:00:00Z"));
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
