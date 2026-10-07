package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.service.DeliveryLane;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.care.service.NotificationDispatchService;
import com.petcare.module.care.service.NotificationDispatchService.BatchOutcome;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Vòng lặp của một lượt ST20: điều kiện dừng, ngân sách thời gian, metrics, cảnh báo trễ, MDC (docs/adr/0012); luồng
 * NORMAL theo lô (docs/adr/0017).
 */
class NotificationOutboxRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final NotificationOutboxProperties PROPS = new NotificationOutboxProperties("*/10 * * * * *",
            "*/5 * * * * *", Duration.ofSeconds(8), Duration.ofSeconds(4), 5, Duration.ofMinutes(1),
            "no-reply@petcare.local", Duration.ofMinutes(15), Duration.ofSeconds(60), "*/5 * * * * *",
            Duration.ofSeconds(3), Duration.ofSeconds(90), 20, Duration.ofSeconds(10));

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final InAppNotificationDeliveryService inApp = mock(InAppNotificationDeliveryService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Đồng hồ ngân sách giả: mỗi lần dispatch "tốn" {@link #costPerDispatch}. */
    private final AtomicLong nanos = new AtomicLong();
    private Duration costPerDispatch = Duration.ZERO;
    private final NotificationOutboxRunner runner = new NotificationOutboxRunner(dispatch, inApp, PROPS,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE), meters, nanos::get);

    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationOutboxRunner.class);
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
        when(dispatch.backlog(any(), any())).thenReturn(new LaneBacklog(0, null));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    /** Lần lượt trả các kết quả, hết thì {@code NONE}; mỗi lần tiêu {@link #costPerDispatch} của ngân sách. */
    private void results(DispatchResult... sequence) {
        List<DispatchResult> remaining = new ArrayList<>(List.of(sequence));
        when(dispatch.dispatchNext(any(), any(), any())).thenAnswer(inv -> {
            nanos.addAndGet(costPerDispatch.toNanos());
            return remaining.isEmpty() ? DispatchResult.NONE : remaining.remove(0);
        });
    }

    /** Luồng NORMAL: lần lượt trả các lô, hết thì {@link BatchOutcome#NONE}; mỗi lô tiêu {@link #costPerDispatch}. */
    private void batches(BatchOutcome... sequence) {
        List<BatchOutcome> remaining = new ArrayList<>(List.of(sequence));
        when(dispatch.dispatchNextBatch(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            nanos.addAndGet(costPerDispatch.toNanos());
            return remaining.isEmpty() ? BatchOutcome.NONE : remaining.remove(0);
        });
    }

    @Test
    void stopsWhenNoDueRowLeft() {
        results(DispatchResult.SENT, DispatchResult.SENT, DispatchResult.NONE);

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(3)).dispatchNext(eq(DeliveryLane.HIGH), eq(NOW),
                eq(new DispatchPolicy(5, Duration.ofMinutes(1), "no-reply@petcare.local")));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY sent=2 retried=0 failed=0 stoppedOnServerError=false");
    }

    /** Lỗi kết nối SMTP: dừng ngay, không thử từng dòng còn lại. */
    @Test
    void stopsOnServerError() {
        results(DispatchResult.SENT, DispatchResult.STOP_RUN, DispatchResult.SENT);

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(2)).dispatchNext(any(), any(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY sent=1 ").contains("stoppedOnServerError=true");
    }

    // ---------------------------------------------------------------- luồng NORMAL theo lô (docs/adr/0017)

    @Test
    void normalLaneSendsInBatchesWithConfiguredSizeAndBudgetNeverOneByOne() {
        batches(new BatchOutcome(20, 20, 0, 0, false), new BatchOutcome(5, 4, 1, 0, false));

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(3)).dispatchNextBatch(eq(NOW),
                eq(new DispatchPolicy(5, Duration.ofMinutes(1), "no-reply@petcare.local")), eq(20),
                eq(Duration.ofSeconds(10)));
        verify(dispatch, never()).dispatchNext(any(), any(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX sent=24 retried=1 failed=0 stoppedOnServerError=false");
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "SENT").counter()
                .count()).isEqualTo(24.0);
    }

    /** Lô báo lỗi máy chủ: dừng lượt; dòng đếm theo kết quả thật, STOP_RUN đếm một lượt dừng. */
    @Test
    void normalBatchStopRunEndsTheRun() {
        batches(new BatchOutcome(20, 3, 1, 1, true), new BatchOutcome(20, 20, 0, 0, false));

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(1)).dispatchNextBatch(any(), any(), anyInt(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX sent=3 retried=1 failed=1 stoppedOnServerError=true");
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "STOP_RUN").counter()
                .count()).isEqualTo(1.0);
    }

    /** Ngân sách NORMAL 8 s: mỗi lô tốn 3 s → 3 lô (0, 3, 6 s) rồi nhường lượt sau, dù còn dòng. */
    @Test
    void normalLaneStopsWhenTimeBudgetIsSpent() {
        costPerDispatch = Duration.ofSeconds(3);
        BatchOutcome full = new BatchOutcome(20, 20, 0, 0, false);
        batches(full, full, full, full, full);

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(3)).dispatchNextBatch(any(), any(), anyInt(), any());
    }

    /** Ngân sách HIGH 4 s: mỗi dòng tốn 1 s → đúng 4 lần gửi rồi nhường lượt sau, dù còn dòng. */
    @Test
    void stopsWhenTimeBudgetIsSpent() {
        costPerDispatch = Duration.ofSeconds(1);
        results(DispatchResult.SENT, DispatchResult.SENT, DispatchResult.SENT, DispatchResult.SENT,
                DispatchResult.SENT, DispatchResult.SENT);

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(4)).dispatchNext(any(), any(), any());
    }

    @Test
    void idleRunLogsNothingAtInfo() {
        batches();

        runner.run(DeliveryLane.NORMAL);

        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void updatesGaugesAndCountersPerLane() {
        batches(new BatchOutcome(2, 1, 1, 0, false));
        when(dispatch.backlog(DeliveryLane.NORMAL, NOW)).thenReturn(new LaneBacklog(7, NOW.minusSeconds(30)));

        runner.run(DeliveryLane.NORMAL);

        assertThat(meters.get("notification.outbox.pending").tag("lane", "NORMAL").gauge().value()).isEqualTo(7.0);
        assertThat(meters.get("notification.outbox.oldest.overdue.seconds").tag("lane", "NORMAL").gauge().value())
                .isEqualTo(30.0);
        assertThat(meters.get("notification.outbox.pending").tag("lane", "HIGH").gauge().value()).isZero();
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "SENT").counter()
                .count()).isEqualTo(1.0);
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "RETRY").counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void warnsWhenPriorityLaneLagsBeyondThreshold() {
        results(DispatchResult.NONE);
        when(dispatch.backlog(DeliveryLane.HIGH, NOW)).thenReturn(new LaneBacklog(3, NOW.minusSeconds(61)));

        runner.run(DeliveryLane.HIGH);

        assertThat(messages(Level.WARN)).singleElement().asString()
                .isEqualTo("NOTIFICATION_OUTBOX_LAGGING lane=HIGH pending=3 overdueSeconds=61");
    }

    @Test
    void noWarningAtThreshold() {
        results(DispatchResult.NONE);
        when(dispatch.backlog(DeliveryLane.HIGH, NOW)).thenReturn(new LaneBacklog(3, NOW.minusSeconds(60)));

        runner.run(DeliveryLane.HIGH);

        assertThat(messages(Level.WARN)).isEmpty();
    }

    /** Lỗi DB: không ném ra scheduler, log kèm số dòng đã xử lý; MDC được gỡ. */
    @Test
    void failureIsLoggedNotThrownAndMdcRemoved() {
        List<String> traceIds = new ArrayList<>();
        when(dispatch.dispatchNext(any(), any(), any())).thenAnswer(inv -> {
            traceIds.add(MDC.get(TraceContext.MDC_KEY));
            return DispatchResult.SENT;
        }).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> runner.run(DeliveryLane.HIGH)).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY_FAILED processedSoFar=1 ");
        assertThat(traceIds).singleElement().asString().startsWith("job-");
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    // ---------------------------------------------------------------- luồng IN_APP (docs/adr/0014)

    /** Lần lượt trả các kết quả của luồng IN_APP, hết thì {@code NONE}. */
    private void inAppResults(DispatchResult... sequence) {
        List<DispatchResult> remaining = new ArrayList<>(List.of(sequence));
        when(inApp.deliverNext(any())).thenAnswer(inv -> {
            nanos.addAndGet(costPerDispatch.toNanos());
            return remaining.isEmpty() ? DispatchResult.NONE : remaining.remove(0);
        });
    }

    @Test
    void inAppLaneDeliversThroughInAppServiceNeverEmail() {
        inAppResults(DispatchResult.SENT, DispatchResult.FAILED, DispatchResult.NONE);

        runner.run(DeliveryLane.IN_APP);

        verify(inApp, times(3)).deliverNext(NOW);
        verify(dispatch, never()).dispatchNext(any(), any(), any());
        verify(dispatch, never()).backlog(any(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_IN_APP sent=1 retried=0 failed=1 stoppedOnServerError=false pending=0");
        assertThat(meters.get("notification.outbox.result").tags("lane", "IN_APP", "result", "SENT").counter()
                .count()).isEqualTo(1.0);
    }

    /** Lượt kết thúc vì hết dòng: tồn đọng = 0, không chạy câu đếm (lúc rảnh còn 1 transaction mỗi lượt). */
    @Test
    void drainedInAppRunSkipsBacklogQuery() {
        inAppResults(DispatchResult.NONE);

        runner.run(DeliveryLane.IN_APP);

        verify(inApp, never()).backlog(any());
        assertThat(meters.get("notification.outbox.pending").tag("lane", "IN_APP").gauge().value()).isZero();
        assertThat(messages(Level.INFO)).isEmpty();
    }

    /** Ngân sách IN_APP 3 s (property riêng): mỗi dòng 1 s → 3 lần; còn dòng nên đếm tồn đọng, cảnh báo theo 90 s. */
    @Test
    void inAppBudgetAndLagWarningUseOwnProperties() {
        costPerDispatch = Duration.ofSeconds(1);
        inAppResults(DispatchResult.SENT, DispatchResult.SENT, DispatchResult.SENT, DispatchResult.SENT);
        when(inApp.backlog(NOW)).thenReturn(new LaneBacklog(5, NOW.minusSeconds(91)));

        runner.run(DeliveryLane.IN_APP);

        verify(inApp, times(3)).deliverNext(any());
        assertThat(meters.get("notification.outbox.pending").tag("lane", "IN_APP").gauge().value()).isEqualTo(5.0);
        assertThat(messages(Level.WARN)).singleElement().asString()
                .isEqualTo("NOTIFICATION_OUTBOX_LAGGING lane=IN_APP pending=5 overdueSeconds=91");
    }

    @Test
    void inAppFailureIsLoggedNotThrown() {
        when(inApp.deliverNext(any())).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> runner.run(DeliveryLane.IN_APP)).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString()
                .startsWith("NOTIFICATION_IN_APP_FAILED processedSoFar=0 ");
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
