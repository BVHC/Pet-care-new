package com.petcare.module.care.job;

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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.service.NotificationCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Hai lượt với mốc cắt riêng, vòng lặp theo lô, ngân sách dùng chung, lịch, log và MDC của job dọn thông báo (docs/adr/0112). */
class NotificationCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-05T20:50:23Z");
    private static final int BATCH = 1000;

    private final NotificationCleanupService cleanup = mock(NotificationCleanupService.class);
    private final AtomicLong nanos = new AtomicLong();
    private final NotificationCleanupJob job = new NotificationCleanupJob(cleanup,
            new NotificationCleanupProperties("23 50 3 * * *", 30, 90, BATCH, Duration.ofSeconds(60)),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), nanos::get);

    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationCleanupJob.class);
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
    void readThenUnreadEachWithItsOwnCutoff() {
        job.run();

        InOrder order = inOrder(cleanup);
        order.verify(cleanup).deleteReadBatch(NOW.minus(Duration.ofDays(30)), BATCH);
        order.verify(cleanup).deleteUnreadBatch(NOW.minus(Duration.ofDays(90)), BATCH);
    }

    @Test
    void keepsDeletingWhileBatchesAreFullAndStopsOnPartialBatch() {
        when(cleanup.deleteReadBatch(any(), anyInt())).thenReturn(BATCH, BATCH, 3);
        when(cleanup.deleteUnreadBatch(any(), anyInt())).thenReturn(7);

        job.run();

        verify(cleanup, times(3)).deleteReadBatch(any(), eq(BATCH));
        verify(cleanup, times(1)).deleteUnreadBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_CLEANUP read=2003 unread=7 ").contains("budgetHit=false");
    }

    /** Ngân sách dùng chung: hết ở lượt đã đọc thì lượt chưa đọc không chạy; phần còn lại dọn lượt sau. */
    @Test
    void budgetSpentInFirstPassSkipsSecondPass() {
        when(cleanup.deleteReadBatch(any(), anyInt())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(40).toNanos());
            return BATCH;
        });

        job.run();

        verify(cleanup, times(2)).deleteReadBatch(any(), eq(BATCH));
        verify(cleanup, never()).deleteUnreadBatch(any(), anyInt());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_CLEANUP read=2000 unread=0 ").contains("budgetHit=true");
    }

    /** Lượt đã đọc xong nhanh, lượt chưa đọc dùng phần ngân sách còn lại rồi dừng. */
    @Test
    void budgetSpentInSecondPassStopsIt() {
        when(cleanup.deleteReadBatch(any(), anyInt())).thenReturn(0);
        when(cleanup.deleteUnreadBatch(any(), anyInt())).thenAnswer(invocation -> {
            nanos.addAndGet(Duration.ofSeconds(70).toNanos());
            return BATCH;
        });

        job.run();

        verify(cleanup, times(1)).deleteUnreadBatch(any(), eq(BATCH));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_CLEANUP read=0 unread=1000 ").contains("budgetHit=true");
    }

    /** Lỗi ở một lô: không ném ra scheduler, log kèm số dòng đã xóa ở các lô trước (đã commit riêng). */
    @Test
    void failureIsLoggedWithCountsSoFarNotThrown() {
        when(cleanup.deleteReadBatch(any(), anyInt())).thenReturn(5);
        when(cleanup.deleteUnreadBatch(any(), anyInt())).thenReturn(BATCH).thenThrow(new IllegalStateException("db"));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("NOTIFICATION_CLEANUP_FAILED readSoFar=5 unreadSoFar=1000 ");
        assertThat(messages(Level.INFO)).isEmpty();
    }

    @Test
    void eachRunHasItsOwnTraceIdThatIsRemovedAfterwards() {
        List<String> seen = new ArrayList<>();
        when(cleanup.deleteReadBatch(any(), anyInt())).thenAnswer(invocation -> {
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
        when(cleanup.deleteReadBatch(any(), anyInt())).thenThrow(new IllegalStateException("db"));

        job.run();

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Scheduled scheduled = NotificationCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.notification-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }

    /** Mỗi lô một transaction ở service (convention 07 §7.4); {@code @Transactional} ở job sẽ gộp mọi lô. */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = NotificationCleanupJob.class.getMethod("run");

        assertThat(NotificationCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(NotificationCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "23 50 3 * * *": 03:50:23 giờ Việt Nam, sau dọn outbox (03:40), giây lệch ST20 và job {@code *}/5. */
    @Test
    void defaultCronFiresAtThreeFiftyTwentyThreeVietnamTime() {
        ZonedDateTime from = ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime next = CronExpression.parse("23 50 3 * * *").next(from);

        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 11, 6, 3, 50, 23, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(next.getSecond() % 5).isNotZero();
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
