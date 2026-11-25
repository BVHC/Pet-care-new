package com.petcare.module.care.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.petcare.module.care.repository.LaneBacklog;
import com.petcare.module.care.service.DeliveryLane;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.care.service.NotificationDispatchService;
import com.petcare.module.care.service.NotificationDispatchService.Claim;
import com.petcare.module.care.service.NotificationDispatchService.DispatchPolicy;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.care.service.NotificationDispatchService.Settled;
import com.petcare.module.care.service.OutboxMailSender;
import com.petcare.module.care.service.OutboxMailSender.SendReport;
import com.petcare.platform.config.TraceContext;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Một lượt chạy của một luồng ST20, dùng chung cho {@link PriorityNotificationJob}, {@link NotificationOutboxJob}
 * (email, docs/adr/0012) và {@link InAppNotificationJob} (docs/adr/0014). Email (docs/adr/0154): mỗi vòng <b>nhận</b>
 * dòng (HIGH một dòng, NORMAL một lô — docs/adr/0017; một transaction) → <b>gửi</b> ngoài transaction
 * ({@link OutboxMailSender}) → <b>ghi kết quả</b> (một transaction). IN_APP: mỗi vòng
 * {@link InAppNotificationDeliveryService#deliverNext}. Lặp tới khi hết dòng đến hạn, gặp lỗi cấp máy chủ SMTP, hoặc
 * hết ngân sách thời gian của luồng (kiểm giữa các vòng). Không {@code @Transactional}. Cuối lượt đếm tồn đọng để cập
 * nhật gauge và cảnh báo trễ; riêng IN_APP, lượt kết thúc vì hết dòng thì tồn đọng là 0 (chỉ luồng này chọn dòng
 * IN_APP), không chạy câu đếm. Lỗi được log và nuốt (docs/adr/0007 mục 5). {@link #sendNow} gửi ngay một dòng HIGH vừa
 * commit (kích sau commit).
 */
@Slf4j
@Component
@EnableConfigurationProperties(NotificationOutboxProperties.class)
public class NotificationOutboxRunner {

    private final NotificationDispatchService dispatch;
    private final OutboxMailSender sender;
    private final InAppNotificationDeliveryService inApp;
    private final NotificationOutboxProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final LongSupplier ticker;
    private final Map<DeliveryLane, AtomicLong> pending = new EnumMap<>(DeliveryLane.class);
    private final Map<DeliveryLane, AtomicLong> overdueSeconds = new EnumMap<>(DeliveryLane.class);

    @Autowired
    public NotificationOutboxRunner(NotificationDispatchService dispatch, OutboxMailSender sender,
            InAppNotificationDeliveryService inApp, NotificationOutboxProperties properties, Clock clock,
            MeterRegistry meters) {
        this(dispatch, sender, inApp, properties, clock, meters, System::nanoTime);
    }

    NotificationOutboxRunner(NotificationDispatchService dispatch, OutboxMailSender sender,
            InAppNotificationDeliveryService inApp, NotificationOutboxProperties properties, Clock clock,
            MeterRegistry meters, LongSupplier ticker) {
        this.dispatch = dispatch;
        this.sender = sender;
        this.inApp = inApp;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.ticker = ticker;
        for (DeliveryLane lane : DeliveryLane.values()) {
            pending.put(lane, gauge("notification.outbox.pending", lane));
            overdueSeconds.put(lane, gauge("notification.outbox.oldest.overdue.seconds", lane));
        }
    }

    public void run(DeliveryLane lane) {
        String job = switch (lane) {
            case HIGH -> "NOTIFICATION_OUTBOX_PRIORITY";
            case NORMAL -> "NOTIFICATION_OUTBOX";
            case IN_APP -> "NOTIFICATION_IN_APP";
        };
        Duration budget = switch (lane) {
            case HIGH -> properties.priorityTimeBudget();
            case NORMAL -> properties.timeBudget();
            case IN_APP -> properties.inAppTimeBudget();
        };
        Duration lagWarn = switch (lane) {
            case HIGH -> properties.lagWarnPriority();
            case NORMAL -> properties.lagWarn();
            case IN_APP -> properties.lagWarnInApp();
        };
        DispatchPolicy policy = policy();
        Map<DispatchResult, Integer> counts = new EnumMap<>(DispatchResult.class);

        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        boolean drained = false;
        try {
            while (ticker.getAsLong() - started < budget.toNanos()) {
                Instant at = Instant.now(clock);
                if (lane.isEmail()) {
                    Claim claim = lane == DeliveryLane.HIGH
                            ? dispatch.claimNextHigh(at, policy, properties.priorityClaimLease())
                            : dispatch.claimNextNormalBatch(at, policy, properties.normalBatchSize(),
                                    properties.normalClaimLease());
                    if (claim.locked() == 0) {
                        drained = true;
                        break;
                    }
                    if (deliver(lane, claim, policy, counts)) {
                        break;
                    }
                    continue;
                }
                DispatchResult result = inApp.deliverNext(at);
                if (result == DispatchResult.NONE) {
                    drained = true;
                    break;
                }
                record(counts, lane, result, 1);
                if (result == DispatchResult.STOP_RUN) {
                    break;
                }
            }

            Instant now = Instant.now(clock);
            LaneBacklog backlog = backlog(lane, now, drained);
            long overdue = backlog.oldestDueAt() == null ? 0
                    : Math.max(0, Duration.between(backlog.oldestDueAt(), now).toSeconds());
            pending.get(lane).set(backlog.pending());
            overdueSeconds.get(lane).set(overdue);

            if (processed(counts) > 0) {
                log.info("{} sent={} retried={} failed={} stoppedOnServerError={} pending={} durationMs={}", job,
                        count(counts, DispatchResult.SENT), count(counts, DispatchResult.RETRY),
                        count(counts, DispatchResult.FAILED), counts.containsKey(DispatchResult.STOP_RUN),
                        backlog.pending(), elapsedMs(started));
            } else {
                log.debug("{} idle pending={}", job, backlog.pending());
            }
            if (overdue > lagWarn.toSeconds()) {
                log.warn("NOTIFICATION_OUTBOX_LAGGING lane={} pending={} overdueSeconds={}", lane, backlog.pending(),
                        overdue);
            }
        } catch (RuntimeException e) {
            log.error("{}_FAILED processedSoFar={} durationMs={}", job, processed(counts), elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /**
     * Gửi ngay dòng HIGH {@code outboxId} vừa commit (docs/adr/0154), gọi từ luồng nền của lần kích. Dòng đã được job
     * HIGH nhận hoặc xử lý thì không làm gì. Không ném: lỗi được log, dòng còn {@code PENDING} để job HIGH quét lại.
     */
    public void sendNow(Long outboxId) {
        MDC.put(TraceContext.MDC_KEY, "kick-" + UUID.randomUUID());
        Map<DispatchResult, Integer> counts = new EnumMap<>(DispatchResult.class);
        try {
            DispatchPolicy policy = policy();
            Claim claim = dispatch.claimHighById(outboxId, Instant.now(clock), policy, properties.priorityClaimLease());
            if (claim.locked() == 0) {
                log.debug("NOTIFICATION_KICK_SKIPPED id={}", outboxId);
                return;
            }
            deliver(DeliveryLane.HIGH, claim, policy, counts);
            log.debug("NOTIFICATION_KICK id={} sent={} retried={} failed={}", outboxId,
                    count(counts, DispatchResult.SENT), count(counts, DispatchResult.RETRY),
                    count(counts, DispatchResult.FAILED));
        } catch (RuntimeException e) {
            log.error("NOTIFICATION_KICK_FAILED id={}", outboxId, e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /**
     * Gửi các thư đã nhận rồi ghi kết quả; trả {@code true} khi có lỗi cấp máy chủ (lượt nên dừng). Dòng đếm theo kết
     * quả thật; {@code STOP_RUN} đếm số lượt dừng vì lỗi máy chủ (docs/adr/0017 mục 6).
     */
    private boolean deliver(DeliveryLane lane, Claim claim, DispatchPolicy policy,
            Map<DispatchResult, Integer> counts) {
        record(counts, lane, DispatchResult.RETRY, claim.retried());
        record(counts, lane, DispatchResult.FAILED, claim.failed());
        SendReport report = sender.send(claim.mails(), lane);
        if (!report.deliveries().isEmpty()) {
            Settled settled = dispatch.settle(lane, Instant.now(clock), policy, report.deliveries());
            record(counts, lane, DispatchResult.SENT, settled.sent());
            record(counts, lane, DispatchResult.RETRY, settled.retried());
            record(counts, lane, DispatchResult.FAILED, settled.failed());
        }
        if (report.stopRun()) {
            record(counts, lane, DispatchResult.STOP_RUN, 1);
            return true;
        }
        return false;
    }

    private DispatchPolicy policy() {
        return new DispatchPolicy(properties.maxAttempts(), properties.initialBackoff(), properties.mailFrom());
    }

    /**
     * IN_APP chỉ có một luồng chọn dòng: kết thúc vì {@code NONE} nghĩa là không còn dòng đến hạn, nên bỏ câu đếm —
     * lúc rảnh mỗi lượt còn một transaction (docs/adr/0014). Email giữ câu đếm vì {@code NONE} có thể do dòng đang bị
     * lượt kia khóa và lượt dừng sớm khi SMTP lỗi.
     */
    private LaneBacklog backlog(DeliveryLane lane, Instant now, boolean drained) {
        if (lane.isEmail()) {
            return dispatch.backlog(lane, now);
        }
        return drained ? new LaneBacklog(0, null) : inApp.backlog(now);
    }

    /** Cộng {@code amount} dòng có kết quả {@code result} vào bộ đếm của lượt và counter của luồng. */
    private void record(Map<DispatchResult, Integer> counts, DeliveryLane lane, DispatchResult result, int amount) {
        if (amount == 0) {
            return;
        }
        counts.merge(result, amount, Integer::sum);
        meters.counter("notification.outbox.result", "lane", lane.name(), "result", result.name()).increment(amount);
    }

    private AtomicLong gauge(String name, DeliveryLane lane) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get).tag("lane", lane.name()).register(meters);
        return value;
    }

    private static int processed(Map<DispatchResult, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int count(Map<DispatchResult, Integer> counts, DispatchResult result) {
        return counts.getOrDefault(result, 0);
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
