package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.returns.api.ReturnRequestQueryApi;
import com.petcare.module.shop.job.OnlineOrderAutoConfirmJob;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.Result;
import com.petcare.support.MutableClock;

/**
 * ST12 (docs/adr/0146, Đơn hàng online#12, BR-BH-12) trên Postgres 17 thật: đơn {@code DELIVERED} quá [CFG]
 * {@code shop.auto_confirm_days} ngày và không có yêu cầu trả hàng đang mở sang {@code COMPLETED}; yêu cầu {@code PENDING}
 * / {@code APPROVED} giữ đơn lại, yêu cầu đã kết thúc thì không; số ngày đọc lúc chạy; đơn đang bị khóa bị bỏ qua (SKIP
 * LOCKED); một đơn lỗi không chặn cả lô. Era <b>năm 1980</b> (job quét không có cận dưới).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OnlineOrderAutoConfirmIT.TestBeans.class})
class OnlineOrderAutoConfirmIT extends OnlineOrderJobItSupport {

    private static final Instant START = Instant.parse("1980-06-15T03:00:00Z");
    private static final Instant RUN = START.plus(30, ChronoUnit.DAYS);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private OnlineOrderAutoConfirmJob job;
    @Autowired
    private OnlineOrderAutoConfirmService service;
    @Autowired
    private ReturnRequestQueryApi returns;
    @Autowired
    private SystemConfigService configService;

    @Override
    protected Instant start() {
        return START;
    }

    /** Đơn đặt rồi ép sang {@code DELIVERED} với {@code delivered_at} cho trước (thỏa mọi CHECK của online_orders). */
    private Placed delivered(Shopper s, Instant deliveredAt) {
        Placed order = place(s, 1);
        Timestamp paid = Timestamp.from(START);
        jdbc.update("""
                UPDATE online_orders SET status = 'DELIVERED', paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                         tracking_code = 'GHN1', delivered_at = ? WHERE id = ?""", paid, paid,
                Timestamp.from(deliveredAt), order.id());
        return order;
    }

    private long returnRequest(long orderId, String status) {
        boolean decided = List.of("APPROVED", "REFUNDED", "REJECTED").contains(status);
        boolean approved = !status.equals("REJECTED") && decided;
        return jdbc.queryForObject("""
                INSERT INTO return_requests (code, order_id, branch_id, status, reason_code, created_by, decided_by,
                                             decided_at, decision_note, restock, refund_amount)
                VALUES (?, ?, ?, ?, 'OTHER', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "TH-IT-" + UUID.randomUUID().toString().substring(0, 10), orderId, branch, status, manager,
                decided ? manager : null, decided ? Timestamp.from(START) : null,
                status.equals("REJECTED") ? "Từ chối" : null, approved ? Boolean.TRUE : null, approved ? 100_000L : null);
    }

    private String completedAt(long id) {
        Timestamp at = jdbc.queryForObject("SELECT completed_at FROM online_orders WHERE id = ?", Timestamp.class, id);
        return at == null ? null : at.toInstant().toString();
    }

    // ================================================================ đường chính

    @Test
    void aDueOrderWithoutAnyReturnRequestBecomesCompletedAtRunTime() {
        Placed due = delivered(a, RUN.minus(8, ChronoUnit.DAYS));
        Placed notYet = delivered(b, RUN.minus(3, ChronoUnit.DAYS));
        clock.set(RUN);
        long noticesBefore = notices(a.accountId(), "ONLINE_ORDER");

        job.run();

        assertThat(onlineStatus(due.id())).isEqualTo("COMPLETED");
        assertThat(completedAt(due.id())).isEqualTo(RUN.toString());
        assertThat(onlineStatus(notYet.id())).as("chưa quá 7 ngày").isEqualTo("DELIVERED");
        assertThat(completedAt(notYet.id())).isNull();
        assertThat(salesStatus(due.orderId())).as("Order không đổi").isEqualTo("PENDING");
        assertThat(notices(a.accountId(), "ONLINE_ORDER")).as("ST12 không thông báo").isEqualTo(noticesBefore);
        job.run();
        assertThat(completedAt(due.id())).as("chạy lại không ghi lại").isEqualTo(RUN.toString());
    }

    @Test
    void theBoundaryIsExactlyAutoConfirmDaysAfterDelivery() {
        Placed exact = delivered(a, RUN.minus(7, ChronoUnit.DAYS));
        Placed oneMicroLater = delivered(b, RUN.minus(7, ChronoUnit.DAYS).plusNanos(1_000));
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(exact.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(oneMicroLater.id())).isEqualTo("DELIVERED");
    }

    @Test
    void anOpenReturnRequestKeepsTheOrderAndAFinishedOneDoesNot() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed pending = delivered(a, old);
        Placed approved = delivered(b, old);
        Placed rejected = delivered(a, old);
        Placed cancelled = delivered(b, old);
        Placed refunded = delivered(a, old);
        returnRequest(pending.orderId(), "PENDING");
        returnRequest(approved.orderId(), "APPROVED");
        returnRequest(rejected.orderId(), "REJECTED");
        returnRequest(cancelled.orderId(), "CANCELLED");
        returnRequest(refunded.orderId(), "REFUNDED");
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(pending.id())).isEqualTo("DELIVERED");
        assertThat(onlineStatus(approved.id())).isEqualTo("DELIVERED");
        assertThat(onlineStatus(rejected.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(cancelled.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(refunded.id())).isEqualTo("COMPLETED");
    }

    @Test
    void aReturnRequestOfAnotherOrderDoesNotHoldThisOne() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed mine = delivered(a, old);
        Placed other = delivered(b, old);
        returnRequest(other.orderId(), "PENDING");
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(mine.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(other.id())).isEqualTo("DELIVERED");
    }

    @Test
    void ordersInOtherStatesAreUntouchedEvenWhenOld() {
        Placed shipped = place(a, 1);
        Timestamp old = Timestamp.from(RUN.minus(20, ChronoUnit.DAYS));
        jdbc.update("""
                UPDATE online_orders SET status = 'SHIPPED', paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                         tracking_code = 'GHN1' WHERE id = ?""", old, old, shipped.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(shipped.id())).isEqualTo("SHIPPED");
    }

    @Test
    void theAutoConfirmDaysIsReadAtRunTime() {
        Placed order = delivered(a, RUN.minus(4, ChronoUnit.DAYS));
        clock.set(RUN);
        job.run();
        assertThat(onlineStatus(order.id())).as("7 ngày: chưa tới hạn").isEqualTo("DELIVERED");
        jdbc.update("UPDATE system_configs SET value = '3' WHERE key = 'shop.auto_confirm_days'");
        configService.reload();
        try {
            job.run();

            assertThat(onlineStatus(order.id())).as("3 ngày: tới hạn từ lần kiểm sau").isEqualTo("COMPLETED");
        } finally {
            jdbc.update("UPDATE system_configs SET value = '7' WHERE key = 'shop.auto_confirm_days'");
            configService.reload();
        }
    }

    @Test
    void theKeysetScanCoversMoreOrdersThanOneBatch() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed first = delivered(a, old);
        Placed second = delivered(b, old);
        Placed third = delivered(a, old);
        clock.set(RUN);

        List<DueOrder> page = service.findDue(RUN, null, 0, 2);
        assertThat(page).hasSize(2);
        assertThat(service.findDue(RUN, page.get(1).deliveredAt(), page.get(1).id(), 2)).hasSize(1);
        job.run();

        assertThat(List.of(first, second, third)).allSatisfy(o -> assertThat(onlineStatus(o.id())).isEqualTo("COMPLETED"));
    }

    // ================================================================ ReturnRequestQueryApi

    @Test
    void returnRequestQueryApiTellsOpenFromFinishedPerOrderAndNeedsACallerTransaction() {
        Placed order = delivered(a, START);
        Placed other = delivered(b, START);
        assertThat(hasOpen(order.orderId())).isFalse();
        returnRequest(order.orderId(), "REJECTED");
        assertThat(hasOpen(order.orderId())).isFalse();
        returnRequest(order.orderId(), "PENDING");
        assertThat(hasOpen(order.orderId())).isTrue();
        assertThat(hasOpen(other.orderId())).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> returns.hasOpenRequestForOrder(order.orderId()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private boolean hasOpen(long orderId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(s -> returns.hasOpenRequestForOrder(orderId)));
    }

    // ================================================================ cuộc đua và lỗi

    @Test
    void anOrderLockedByAnotherTransactionIsSkippedThenConfirmedOnTheNextRun() throws Exception {
        Placed order = delivered(a, RUN.minus(10, ChronoUnit.DAYS));
        clock.set(RUN);

        whileHeld(order.id(), () -> {
        }, job::run);

        assertThat(onlineStatus(order.id())).as("đang bị khóa: lượt này bỏ qua").isEqualTo("DELIVERED");
        job.run();
        assertThat(onlineStatus(order.id())).isEqualTo("COMPLETED");
    }

    @Test
    void anOrderTheCustomerConfirmedWhileLockedStaysCompletedAndIsNotWrittenAgain() throws Exception {
        Placed order = delivered(a, RUN.minus(10, ChronoUnit.DAYS));
        clock.set(RUN);
        Instant customerConfirmedAt = RUN.minusSeconds(60);

        whileHeld(order.id(), () -> jdbc.update(
                "UPDATE online_orders SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(customerConfirmedAt), order.id()), job::run);
        job.run();

        assertThat(completedAt(order.id())).isEqualTo(customerConfirmedAt.toString());
    }

    @Test
    void anOpenReturnRequestCreatedBetweenTheScanAndTheLockIsReCheckedAndSkipped() {
        Placed order = delivered(a, RUN.minus(10, ChronoUnit.DAYS));
        clock.set(RUN);
        DueOrder scanned = service.findDue(RUN, null, 0, 10).get(0);
        assertThat(scanned.id()).isEqualTo(order.id());
        returnRequest(order.orderId(), "PENDING");

        assertThat(service.confirm(scanned.id())).isEqualTo(Result.SKIPPED);

        assertThat(onlineStatus(order.id())).isEqualTo("DELIVERED");
    }

    @Test
    void aFailingOrderRollsBackAloneAndDoesNotStopTheBatchThenRetriesNextRun() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed broken = delivered(a, old);
        Placed fine = delivered(b, old);
        failUpdatesOf(broken.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(fine.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(broken.id())).isEqualTo("DELIVERED");

        dropFailTrigger();
        job.run();

        assertThat(onlineStatus(broken.id())).isEqualTo("COMPLETED");
        assertThat(Map.of("fine", completedAt(fine.id()))).containsEntry("fine", RUN.toString());
    }
}
