package com.petcare.module.identity.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.petcare.module.identity.service.PendingAccountCleanupService;
import com.petcare.module.identity.service.PendingAccountCleanupService.PurgeResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * ST02 (docs/adr/0013): keyset theo lô, một tài khoản lỗi không dừng lượt, time budget, cảnh báo xóa chậm, log, MDC,
 * lịch chạy. Thời gian đo bằng ticker giả: mỗi lần {@code purge} dịch ticker theo {@link #purgeTook}.
 */
class PendingAccountCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final int BATCH = 3;

    private final PendingAccountCleanupService cleanup = mock(PendingAccountCleanupService.class);
    private final AtomicLong ticker = new AtomicLong();
    /** Thời gian xóa giả theo id; id không có trong map xóa trong 10 ms. */
    private Map<Long, Duration> purgeTook = Map.of();

    private final Logger logger = (Logger) LoggerFactory.getLogger(PendingAccountCleanupJob.class);
    /** Chụp MDC ngay lúc ghi log: logback đọc MDC lười, sau khi job gỡ traceId thì không còn để kiểm. */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(cleanup.findExpiredIds(any(), anyLong(), anyInt())).thenReturn(List.of());
        when(cleanup.purge(anyLong(), any())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            ticker.addAndGet(purgeTook.getOrDefault(id, Duration.ofMillis(10)).toNanos());
            return PurgeResult.DELETED;
        });
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    // ---------------------------------------------------------------- keyset, một lần chốt now

    @Test
    void readsBatchesByKeysetWithNowFixedOnceAndPurgesEveryId() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenReturn(List.of(1L, 2L, 3L));
        when(cleanup.findExpiredIds(NOW, 3L, BATCH)).thenReturn(List.of(4L));

        job(Duration.ofSeconds(60)).run();

        InOrder order = inOrder(cleanup);
        order.verify(cleanup).findExpiredIds(NOW, 0L, BATCH);
        order.verify(cleanup).purge(1L, NOW);
        order.verify(cleanup).purge(2L, NOW);
        order.verify(cleanup).purge(3L, NOW);
        order.verify(cleanup).findExpiredIds(NOW, 3L, BATCH);
        order.verify(cleanup).purge(4L, NOW);
        order.verifyNoMoreInteractions();
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP deleted=4 skipped=0 failed=0 ")
                .contains("stoppedByBudget=false");
    }

    @Test
    void emptyRunIsOneQueryAndLogsZero() {
        job(Duration.ofSeconds(60)).run();

        verify(cleanup).findExpiredIds(NOW, 0L, BATCH);
        verify(cleanup, never()).purge(anyLong(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP deleted=0 skipped=0 failed=0 maxPurgeMs=0 avgPurgeMs=0 ");
    }

    @Test
    void skippedAccountsAreCounted() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenReturn(List.of(1L, 2L));
        doReturn(PurgeResult.SKIPPED).when(cleanup).purge(2L, NOW);

        job(Duration.ofSeconds(60)).run();

        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP deleted=1 skipped=1 failed=0 ");
    }

    // ---------------------------------------------------------------- lỗi

    /**
     * Lỗi ở một tài khoản (transaction của nó đã rollback): log có id, các tài khoản sau vẫn xử lý, keyset đi qua id
     * lỗi nên lượt này không đọc lại nó.
     */
    @Test
    void failingAccountIsLoggedAndRunContinues() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new UnsupportedOperationException("nợ D001")).when(cleanup).purge(2L, NOW);

        assertThatCode(job(Duration.ofSeconds(60))::run).doesNotThrowAnyException();

        verify(cleanup).purge(3L, NOW);
        verify(cleanup).findExpiredIds(NOW, 3L, BATCH);
        assertThat(messages(Level.ERROR)).singleElement().asString()
                .isEqualTo("PENDING_ACCOUNT_CLEANUP_FAILED accountId=2");
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP deleted=2 skipped=0 failed=1 ");
    }

    @Test
    void queryFailureIsLoggedNotThrown() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenThrow(new IllegalStateException("db"));

        assertThatCode(job(Duration.ofSeconds(60))::run).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP_FAILED deletedSoFar=0 ");
        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    // ---------------------------------------------------------------- time budget, xóa chậm (nợ D009)

    /** Budget 60 s, mỗi tài khoản 25 s: xóa 3 (0 s, 25 s, 50 s), tới 75 s thì dừng; không đọc lô tiếp. */
    @Test
    void stopsWhenTimeBudgetIsUsedUp() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenReturn(List.of(1L, 2L, 3L));
        when(cleanup.findExpiredIds(NOW, 3L, BATCH)).thenReturn(List.of(4L, 5L, 6L));
        purgeTook = Map.of(1L, Duration.ofSeconds(25), 2L, Duration.ofSeconds(25), 3L, Duration.ofSeconds(25),
                4L, Duration.ofSeconds(25));

        job(Duration.ofSeconds(60)).run();

        verify(cleanup).purge(3L, NOW);
        verify(cleanup).findExpiredIds(NOW, 3L, BATCH);
        verify(cleanup, never()).purge(eq(4L), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("PENDING_ACCOUNT_CLEANUP deleted=3 ").contains("stoppedByBudget=true");
    }

    /** Ngưỡng {@code slowWarn} 1 s: đúng 1 s không cảnh báo, 1,5 s cảnh báo; log lượt có max/avg. */
    @Test
    void slowPurgeIsWarnedAndTimingsAreLogged() {
        when(cleanup.findExpiredIds(NOW, 0L, BATCH)).thenReturn(List.of(1L, 2L));
        purgeTook = Map.of(1L, Duration.ofSeconds(1), 2L, Duration.ofMillis(1500));

        job(Duration.ofSeconds(60)).run();

        assertThat(messages(Level.WARN)).containsExactly("PENDING_ACCOUNT_CLEANUP_SLOW accountId=2 durationMs=1500");
        assertThat(messages(Level.INFO)).singleElement().asString()
                .contains("maxPurgeMs=1500 avgPurgeMs=1250 ");
    }

    // ---------------------------------------------------------------- MDC, lịch chạy

    @Test
    void eachRunHasItsOwnTraceIdThatIsRemovedAfterwards() {
        List<String> seen = new ArrayList<>();
        when(cleanup.findExpiredIds(any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            seen.add(MDC.get(TraceContext.MDC_KEY));
            return List.of();
        });
        PendingAccountCleanupJob job = job(Duration.ofSeconds(60));

        job.run();
        job.run();

        assertThat(seen).hasSize(2).allSatisfy(id -> assertThat(id).startsWith("job-"));
        assertThat(seen.get(0)).isNotEqualTo(seen.get(1));
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getMDCPropertyMap().get(TraceContext.MDC_KEY)).startsWith("job-"));
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    /** Cron lấy từ property và chạy theo giờ Việt Nam; nếu quên zone, scheduler dùng timezone JVM (container: UTC). */
    @Test
    void scheduledFromPropertyInBusinessZone() throws NoSuchMethodException {
        Scheduled scheduled = PendingAccountCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.pending-account-cleanup.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }

    /** Mỗi tài khoản một transaction ở service (convention 07 §7.4); job mà {@code @Transactional} thì gộp tất cả. */
    @Test
    void jobItselfIsNotTransactional() throws NoSuchMethodException {
        Method run = PendingAccountCleanupJob.class.getMethod("run");

        assertThat(PendingAccountCleanupJob.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(PendingAccountCleanupJob.class.isAnnotationPresent(jakarta.transaction.Transactional.class))
                .isFalse();
        assertThat(run.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(run.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    }

    /** Lịch mặc định "0 *&#47;15 * * * *": phút 0, 15, 30, 45 mỗi giờ. */
    @Test
    void defaultCronFiresEveryFifteenMinutes() {
        CronExpression cron = CronExpression.parse("0 */15 * * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 10, 7, 10, 1, 0, 0, TimeConfig.BUSINESS_ZONE);

        ZonedDateTime first = cron.next(from);

        assertThat(first).isEqualTo(ZonedDateTime.of(2026, 10, 7, 10, 15, 0, 0, TimeConfig.BUSINESS_ZONE));
        assertThat(cron.next(first)).isEqualTo(first.plusMinutes(15));
    }

    // ---------------------------------------------------------------- helpers

    private PendingAccountCleanupJob job(Duration timeBudget) {
        return new PendingAccountCleanupJob(cleanup,
                new PendingAccountCleanupProperties("0 */15 * * * *", BATCH, timeBudget, Duration.ofSeconds(1)),
                Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), ticker::get);
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
