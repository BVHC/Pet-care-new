package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.service.DeliveryLane;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.care.service.NotificationDispatchService;
import com.petcare.module.care.service.NotificationDispatchService.Claim;
import com.petcare.module.care.service.NotificationDispatchService.ClaimedMail;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.care.service.NotificationDispatchService.Settled;
import com.petcare.module.care.service.OutboxMailSender;
import com.petcare.module.care.service.OutboxMailSender.Delivery;
import com.petcare.module.care.service.OutboxMailSender.Outcome;
import com.petcare.module.care.service.OutboxMailSender.SendReport;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Vòng lặp của một lượt ST20: điều kiện dừng, ngân sách thời gian, metrics, cảnh báo trễ, MDC (docs/adr/0012); luồng
 * NORMAL theo lô (docs/adr/0017); thứ tự nhận → gửi → ghi kết quả và kích gửi ngay một dòng HIGH (docs/adr/0154).
 */
class NotificationOutboxRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final NotificationOutboxProperties PROPS = new NotificationOutboxProperties("*/10 * * * * *",
            "*/5 * * * * *", Duration.ofSeconds(8), Duration.ofSeconds(4), 5, Duration.ofMinutes(1),
            "no-reply@petcare.local", Duration.ofMinutes(15), Duration.ofSeconds(60), "*/5 * * * * *",
            Duration.ofSeconds(3), Duration.ofSeconds(90), 20, Duration.ofSeconds(60), Duration.ofMinutes(10));
    private static final DispatchPolicy POLICY = new DispatchPolicy(5, Duration.ofMinutes(1), "no-reply@petcare.local");

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final OutboxMailSender sender = mock(OutboxMailSender.class);
    private final InAppNotificationDeliveryService inApp = mock(InAppNotificationDeliveryService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Đồng hồ ngân sách giả: mỗi lần dispatch "tốn" {@link #costPerDispatch}. */
    private final AtomicLong nanos = new AtomicLong();
    private Duration costPerDispatch = Duration.ZERO;
    private final NotificationOutboxRunner runner = new NotificationOutboxRunner(dispatch, sender, inApp, PROPS,
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
        when(dispatch.claimNextHigh(any(), any(), any())).thenReturn(Claim.NONE);
        when(dispatch.claimNextNormalBatch(any(), any(), anyInt(), any())).thenReturn(Claim.NONE);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    /**
     * Một vòng email giả: bước nhận trả {@code locked} dòng, trong đó {@code sent} gửi được, {@code retried} hẹn thử
     * lại, {@code failed} lỗi; {@code stopRun} khi bước gửi báo lỗi máy chủ.
     */
    private record Round(int locked, int sent, int retried, int failed, boolean stopRun) {
    }

    private static Round round(int sent, int retried, int failed, boolean stopRun) {
        return new Round(sent + retried + failed, sent, retried, failed, stopRun);
    }

    /**
     * Lần lượt trả các vòng cho luồng {@code lane}, hết thì {@link Claim#NONE}; mỗi lần nhận tiêu
     * {@link #costPerDispatch} của ngân sách. Bước gửi và bước ghi trả đúng số đếm của vòng.
     */
    private void rounds(DeliveryLane lane, Round... sequence) {
        List<Round> remaining = new ArrayList<>(List.of(sequence));
        List<Round> current = new ArrayList<>();
        org.mockito.stubbing.Answer<Claim> claim = inv -> {
            nanos.addAndGet(costPerDispatch.toNanos());
            if (remaining.isEmpty()) {
                return Claim.NONE;
            }
            Round next = remaining.remove(0);
            current.clear();
            current.add(next);
            List<ClaimedMail> mails = new ArrayList<>();
            for (long id = 1; id <= next.locked(); id++) {
                mails.add(new ClaimedMail(id, NOW, NOW, true, null));
            }
            return new Claim(next.locked(), mails, 0, 0);
        };
        if (lane == DeliveryLane.HIGH) {
            when(dispatch.claimNextHigh(any(), any(), any())).thenAnswer(claim);
        } else {
            when(dispatch.claimNextNormalBatch(any(), any(), anyInt(), any())).thenAnswer(claim);
        }
        when(sender.send(anyList(), eq(lane))).thenAnswer(inv -> {
            List<ClaimedMail> mails = inv.getArgument(0);
            List<Delivery> deliveries = mails.stream().map(m -> new Delivery(m, Outcome.SENT, null)).toList();
            return new SendReport(deliveries, current.get(0).stopRun());
        });
        when(dispatch.settle(eq(lane), any(), any(), anyList())).thenAnswer(inv -> {
            Round r = current.get(0);
            return new Settled(r.sent(), r.retried(), r.failed(), 0);
        });
    }

    @Test
    void stopsWhenNoDueRowLeft() {
        rounds(DeliveryLane.HIGH, round(1, 0, 0, false), round(1, 0, 0, false));

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(3)).claimNextHigh(NOW, POLICY, Duration.ofSeconds(60));
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY sent=2 retried=0 failed=0 stoppedOnServerError=false");
    }

    /** Nhận (transaction) → gửi (ngoài transaction) → ghi kết quả (transaction), đúng thứ tự, cùng các thư đã nhận. */
    @Test
    void claimsThenSendsThenSettles() {
        rounds(DeliveryLane.HIGH, round(1, 0, 0, false));

        runner.run(DeliveryLane.HIGH);

        InOrder order = inOrder(dispatch, sender);
        order.verify(dispatch).claimNextHigh(NOW, POLICY, Duration.ofSeconds(60));
        order.verify(sender).send(anyList(), eq(DeliveryLane.HIGH));
        order.verify(dispatch).settle(eq(DeliveryLane.HIGH), eq(NOW), eq(POLICY), anyList());
    }

    /** Lỗi cấp máy chủ SMTP: dừng ngay, không nhận thêm dòng. */
    @Test
    void stopsOnServerError() {
        rounds(DeliveryLane.HIGH, round(1, 0, 0, false), round(0, 1, 0, true), round(1, 0, 0, false));

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(2)).claimNextHigh(any(), any(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY sent=1 retried=1 ").contains("stoppedOnServerError=true");
    }

    /** Mọi dòng của vòng dựng thư lỗi: không gọi SMTP, không mở transaction ghi kết quả; số đếm lấy từ bước nhận. */
    @Test
    void roundWithoutSendableMailSkipsSmtpAndSettle() {
        when(dispatch.claimNextHigh(any(), any(), any())).thenReturn(new Claim(1, List.of(), 0, 1), Claim.NONE);
        when(sender.send(anyList(), any())).thenReturn(SendReport.EMPTY);

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, never()).settle(any(), any(), any(), anyList());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX_PRIORITY sent=0 retried=0 failed=1");
    }

    // ---------------------------------------------------------------- luồng NORMAL theo lô (docs/adr/0017)

    @Test
    void normalLaneSendsInBatchesWithConfiguredSizeAndLeaseNeverOneByOne() {
        rounds(DeliveryLane.NORMAL, round(20, 0, 0, false), round(4, 1, 0, false));

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(3)).claimNextNormalBatch(NOW, POLICY, 20, Duration.ofMinutes(10));
        verify(dispatch, never()).claimNextHigh(any(), any(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX sent=24 retried=1 failed=0 stoppedOnServerError=false");
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "SENT").counter()
                .count()).isEqualTo(24.0);
    }

    /** Lô báo lỗi máy chủ: dừng lượt; dòng đếm theo kết quả thật, STOP_RUN đếm một lượt dừng. */
    @Test
    void normalBatchStopRunEndsTheRun() {
        rounds(DeliveryLane.NORMAL, round(3, 1, 1, true), round(20, 0, 0, false));

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(1)).claimNextNormalBatch(any(), any(), anyInt(), any());
        assertThat(messages(Level.INFO)).singleElement().asString()
                .startsWith("NOTIFICATION_OUTBOX sent=3 retried=1 failed=1 stoppedOnServerError=true");
        assertThat(meters.get("notification.outbox.result").tags("lane", "NORMAL", "result", "STOP_RUN").counter()
                .count()).isEqualTo(1.0);
    }

    /** Ngân sách NORMAL 8 s: mỗi lô tốn 3 s → 3 lô (0, 3, 6 s) rồi nhường lượt sau, dù còn dòng. */
    @Test
    void normalLaneStopsWhenTimeBudgetIsSpent() {
        costPerDispatch = Duration.ofSeconds(3);
        Round full = round(20, 0, 0, false);
        rounds(DeliveryLane.NORMAL, full, full, full, full, full);

        runner.run(DeliveryLane.NORMAL);

        verify(dispatch, times(3)).claimNextNormalBatch(any(), any(), anyInt(), any());
    }

    /** Ngân sách HIGH 4 s: mỗi dòng tốn 1 s → đúng 4 lần gửi rồi nhường lượt sau, dù còn dòng. */
    @Test
    void stopsWhenTimeBudgetIsSpent() {
        costPerDispatch = Duration.ofSeconds(1);
        Round one = round(1, 0, 0, false);
        rounds(DeliveryLane.HIGH, one, one, one, one, one, one);

        runner.run(DeliveryLane.HIGH);

        verify(dispatch, times(4)).claimNextHigh(any(), any(), any());
    }

    @Test
    void idleRunLogsNothingAtInfo() {
        rounds(DeliveryLane.NORMAL);

        runner.run(DeliveryLane.NORMAL);

        assertThat(messages(Level.INFO)).isEmpty();
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void updatesGaugesAndCountersPerLane() {
        rounds(DeliveryLane.NORMAL, round(1, 1, 0, false));
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
        rounds(DeliveryLane.HIGH);
        when(dispatch.backlog(DeliveryLane.HIGH, NOW)).thenReturn(new LaneBacklog(3, NOW.minusSeconds(61)));

        runner.run(DeliveryLane.HIGH);

        assertThat(messages(Level.WARN)).singleElement().asString()
                .isEqualTo("NOTIFICATION_OUTBOX_LAGGING lane=HIGH pending=3 overdueSeconds=61");
    }

    @Test
    void noWarningAtThreshold() {
        rounds(DeliveryLane.HIGH);
        when(dispatch.backlog(DeliveryLane.HIGH, NOW)).thenReturn(new LaneBacklog(3, NOW.minusSeconds(60)));

        runner.run(DeliveryLane.HIGH);

        assertThat(messages(Level.WARN)).isEmpty();
    }

    /** Lỗi DB: không ném ra scheduler, log kèm số dòng đã xử lý; MDC được gỡ. */
    @Test
    void failureIsLoggedNotThrownAndMdcRemoved() {
        List<String> traceIds = new ArrayList<>();
        rounds(DeliveryLane.HIGH, round(1, 0, 0, false), round(1, 0, 0, false));
        doAnswer(inv -> {
            traceIds.add(MDC.get(TraceContext.MDC_KEY));
            return new Settled(1, 0, 0, 0);
        }).doThrow(new IllegalStateException("db")).when(dispatch).settle(any(), any(), any(), anyList());

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
        verify(dispatch, never()).claimNextHigh(any(), any(), any());
        verify(sender, never()).send(anyList(), any());
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

    // ---------------------------------------------------------------- kích gửi ngay sau commit (docs/adr/0154)

    @Test
    void sendNowClaimsThatRowSendsAndSettlesUnderKickTraceId() {
        ClaimedMail mail = new ClaimedMail(42L, NOW, NOW, true, null);
        when(dispatch.claimHighById(42L, NOW, POLICY, Duration.ofSeconds(60)))
                .thenReturn(new Claim(1, List.of(mail), 0, 0));
        when(sender.send(List.of(mail), DeliveryLane.HIGH))
                .thenReturn(new SendReport(List.of(new Delivery(mail, Outcome.SENT, null)), false));
        List<String> traceIds = new ArrayList<>();
        when(dispatch.settle(eq(DeliveryLane.HIGH), eq(NOW), eq(POLICY), anyList())).thenAnswer(inv -> {
            traceIds.add(MDC.get(TraceContext.MDC_KEY));
            return new Settled(1, 0, 0, 0);
        });

        runner.sendNow(42L);

        InOrder order = inOrder(dispatch, sender);
        order.verify(dispatch).claimHighById(42L, NOW, POLICY, Duration.ofSeconds(60));
        order.verify(sender).send(List.of(mail), DeliveryLane.HIGH);
        order.verify(dispatch).settle(eq(DeliveryLane.HIGH), eq(NOW), eq(POLICY), anyList());
        assertThat(traceIds).singleElement().asString().startsWith("kick-");
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        assertThat(meters.get("notification.outbox.result").tags("lane", "HIGH", "result", "SENT").counter()
                .count()).isEqualTo(1.0);
    }

    /** Dòng đã được job HIGH nhận hoặc xử lý: kích không gửi gì. */
    @Test
    void sendNowSkipsRowAlreadyTaken() {
        when(dispatch.claimHighById(anyLong(), any(), any(), any())).thenReturn(Claim.NONE);

        runner.sendNow(42L);

        verify(sender, never()).send(anyList(), any());
        verify(dispatch, never()).settle(any(), any(), any(), anyList());
    }

    /** Lỗi (ví dụ DB) không thoát ra luồng nền: log {@code NOTIFICATION_KICK_FAILED}, dòng để job HIGH quét lại. */
    @Test
    void sendNowFailureIsLoggedNotThrown() {
        doThrow(new IllegalStateException("db")).when(dispatch).claimHighById(anyLong(), any(), any(), any());

        assertThatCode(() -> runner.sendNow(42L)).doesNotThrowAnyException();

        assertThat(messages(Level.ERROR)).singleElement().asString().isEqualTo("NOTIFICATION_KICK_FAILED id=42");
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
