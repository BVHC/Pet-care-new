package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.service.NotificationOutboxCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Mốc cắt theo trạng thái, vòng lặp theo lô, lịch chạy, log và MDC của job dọn outbox (docs/adr/0007, 0016). */
class NotificationOutboxCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-07T20:40:00Z");
    private static final int BATCH = 1000;

    private final NotificationOutboxCleanupService cleanup = mock(NotificationOutboxCleanupService.class);
    private final NotificationOutboxCleanupJob job = new NotificationOutboxCleanupJob(cleanup,
            new NotificationOutboxCleanupProperties("0 40 3 * * *", 30, 90, BATCH),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationOutboxCleanupJob.class);
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
    void sentThenFailedEachWithItsOwnRetention() {
        job.run();

        InOrder order = inOrder(cleanup);
        order.verify(cleanup).deleteProcessedBatch(OutboxStatus.SENT, NOW.minus(Duration.ofDays(30)), BATCH);
        order.verify(cleanup).deleteProcessedBatch(OutboxStatus.FAILED, NOW.minus(Duration.ofDays(90)), BATCH);
    }

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsOnPartialBatch() {
        when(cleanup.deleteProcessedBatch(eq(OutboxStatus.SENT), any(), anyInt())).thenReturn(BATCH, BATCH, 3);
        when(cleanup.deleteProcessedBatch(eq(OutboxStatus.FAILED), any(), anyInt())).thenReturn(7);

        job.run();

        verify(cleanup, times(3)).deleteProcessedBatch(eq(OutboxStatus.SENT), any(), eq(BATCH));
        verify(cleanup, times(1)).deleteProcessedBatch(eq(OutboxStatus.FAILED), any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_CLEANUP sent=2003 failed=7 ");
    }

    /** Lỗi ở một lô: không ném ra scheduler, log kèm số dòng đã xóa ở các lô trước (đã commit riêng). */
    @Test
    void failureIsLoggedWithCountsSoFarNotThrown() {
        when(cleanup.deleteProcessedBatch(eq(OutboxStatus.SENT), any(), anyInt())).thenReturn(BATCH, 5);
        when(cleanup.deleteProcessedBatch(eq(OutboxStatus.FAILED), any(), anyInt()))
                .thenReturn(BATCH).thenThrow(new IllegalStateException("db"));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_CLEANUP_FAILED sentSoFar=1005 failedSoFar=1000 ");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void eachRunHasItsOwnTraceIdThatIsRemovedAfterwards() {
        List<String> seen = new ArrayList<>();
        when(cleanup.deleteProcessedBatch(eq(OutboxStatus.SENT), any(), anyInt())).thenAnswer(invocation -> {
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
        when(cleanup.deleteProcessedBatch(any(), any(), anyInt())).thenThrow(new IllegalStateException("db"));

        job.run();

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Scheduled scheduled = NotificationOutboxCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.notification-outbox-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }

    /** Mỗi lô một transaction ở service (convention 07 §7.4); {@code @Transactional} ở job sẽ gộp mọi lô. */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = NotificationOutboxCleanupJob.class.getMethod("run");

        assertThat(NotificationOutboxCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(NotificationOutboxCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class))
                .isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "0 40 3 * * *": 03:40 giờ Việt Nam, không trùng dọn phiên (03:00) và mốc 15 phút của ST02. */
    @Test
    void defaultCronFiresAtThreeFortyVietnamTime() {
        ZonedDateTime from = ZonedDateTime.of(2026, 10, 7, 10, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime next = CronExpression.parse("0 40 3 * * *").next(from);

        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 10, 8, 3, 40, 0, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(next.getMinute() % 15).isNotZero();
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
