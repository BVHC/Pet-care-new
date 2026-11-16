package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.shop.job.OnlineOrderPaymentExpiryJob;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.Result;
import com.petcare.support.MutableClock;

/**
 * ST10 (docs/adr/0146, Đơn hàng online#4, BR-BH-12) trên Postgres 17 thật: đơn quá hạn thanh toán bị hủy cùng Order,
 * giao dịch {@code PENDING}, phần giữ hàng và thông báo; đơn chưa tới hạn hoặc đã rời {@code PENDING_PAYMENT} không bị
 * đụng; đơn đang bị khóa bị bỏ qua (SKIP LOCKED) rồi làm ở lượt sau; một đơn lỗi không chặn cả lô. Era <b>năm 1979</b>
 * (job quét không có cận dưới nên IT cần era cũ hơn mọi IT khác).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OnlineOrderPaymentExpiryIT.TestBeans.class})
class OnlineOrderPaymentExpiryIT extends OnlineOrderJobItSupport {

    private static final Instant START = Instant.parse("1979-06-15T03:00:00Z");
    /** Hạn thanh toán mặc định 24 giờ ([CFG] shop.payment_window_hours). */
    private static final Instant RUN = START.plusSeconds(25 * 3600);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private OnlineOrderPaymentExpiryJob job;
    @Autowired
    private OnlineOrderPaymentExpiryService service;

    @Override
    protected Instant start() {
        return START;
    }

    // ================================================================ đường chính

    @Test
    void aDueOrderIsCancelledWithItsOrderTransactionReservationAndNotice() {
        Placed due = place(a, 2);
        long txn = transaction(due.id(), "PENDING", START);
        clock.set(START.plusSeconds(2 * 3600));
        Placed later = place(b, 3);
        clock.set(RUN);

        job.run();

        Map<String, Object> online = jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?", due.id());
        assertThat(online).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "Quá hạn thanh toán");
        assertThat(online.get("cancelled_by")).as("hệ thống hủy").isNull();
        assertThat(((Timestamp) online.get("cancelled_at")).toInstant()).isEqualTo(RUN);
        Map<String, Object> sales = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", due.orderId());
        assertThat(sales).containsEntry("status", "CANCELLED").containsEntry("cancel_type", "ONLINE_EXPIRED")
                .containsEntry("cancel_reason", "Quá hạn thanh toán");
        assertThat(sales.get("cancelled_by")).isNull();
        assertThat(((Timestamp) sales.get("cancelled_at")).toInstant()).isEqualTo(RUN);
        assertThat(reservationStatus(due.id())).isEqualTo("RELEASED");
        assertThat(transactionStatus(txn)).isEqualTo("CANCELLED");
        assertThat(notices(a.accountId(), "ONLINE_ORDER_CANCELLED_APP")).isEqualTo(1);
        assertThat(emails(accountEmail(a), "ONLINE_ORDER_CANCELLED")).isEqualTo(1);
        Map<String, Object> notice = jdbc.queryForMap("SELECT payload::text AS payload FROM notification_outbox "
                + "WHERE recipient_account_id = ? AND template_code = 'ONLINE_ORDER_CANCELLED_APP'", a.accountId());
        assertThat((String) notice.get("payload")).contains(due.code()).contains("Quá hạn thanh toán");

        assertThat(onlineStatus(later.id())).as("chưa tới hạn").isEqualTo("PENDING_PAYMENT");
        assertThat(salesStatus(later.orderId())).isEqualTo("PENDING");
        assertThat(reservationStatus(later.id())).isEqualTo("HELD");
        assertThat(notices(b.accountId(), "ONLINE_ORDER_CANCELLED")).isZero();
    }

    @Test
    void theReleasedUnitsAreAvailableAgainAndARerunChangesNothing() {
        Placed due = place(a, 99);
        clock.set(RUN);
        job.run();

        job.run();

        assertThat(onlineStatus(due.id())).isEqualTo("CANCELLED");
        assertThat(notices(a.accountId(), "ONLINE_ORDER_CANCELLED")).as("một thông báo trong app").isEqualTo(1);
        assertThat(emails(accountEmail(a), "ONLINE_ORDER_CANCELLED")).as("một email").isEqualTo(1);
        assertThat(reservationStatus(due.id())).isEqualTo("RELEASED");
        // phiên cũ đã hết hạn theo đồng hồ giả (12 giờ): mở lại token rồi đặt lại đúng số lượng vừa nhả
        place(new Shopper(b.accountId(), b.customerId(), b.addressId(), token(b.accountId())), 99);
    }

    @Test
    void ordersThatLeftPendingPaymentAreUntouchedEvenWhenTheirDueTimeIsPast() {
        Placed paid = place(a, 1);
        jdbc.update("UPDATE online_orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                paid.id());
        Placed cancelled = place(b, 1);
        jdbc.update("UPDATE online_orders SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = 'x' WHERE id = ?",
                Timestamp.from(START), cancelled.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(paid.id())).isEqualTo("PAID");
        assertThat(reservationStatus(paid.id())).as("đơn đã trả tiền giữ nguyên phần giữ").isEqualTo("HELD");
        assertThat(salesStatus(paid.orderId())).isEqualTo("PENDING");
        assertThat(onlineStatus(cancelled.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM online_orders WHERE id = ?", String.class,
                cancelled.id())).isEqualTo("x");
        assertThat(notices(a.accountId(), "ONLINE_ORDER_CANCELLED")).isZero();
    }

    @Test
    void anOrderWithASucceededTransactionIsNotCancelled() {
        Placed order = place(a, 1);
        transaction(order.id(), "SUCCEEDED", START);
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(order.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(reservationStatus(order.id())).isEqualTo("HELD");
        assertThat(salesStatus(order.orderId())).isEqualTo("PENDING");
    }

    @Test
    void anOrderDueExactlyAtTheRunTimeIsCancelledAndOneMicrosecondLaterIsNot() {
        Placed exact = place(a, 1);
        Placed later = place(b, 1);
        Instant exactDue = START.plusSeconds(3600);
        jdbc.update("UPDATE online_orders SET payment_due_at = ? WHERE id = ?", Timestamp.from(exactDue), exact.id());
        jdbc.update("UPDATE online_orders SET payment_due_at = ? WHERE id = ?",
                Timestamp.from(exactDue.plusNanos(1_000)), later.id());
        clock.set(exactDue);

        job.run();

        assertThat(onlineStatus(exact.id())).isEqualTo("CANCELLED");
        assertThat(onlineStatus(later.id())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void theKeysetScanCoversMoreOrdersThanOneBatch() {
        Placed first = place(a, 1);
        Placed second = place(b, 1);
        Placed third = place(a, 1);
        clock.set(RUN);

        assertThat(service.findDue(RUN, null, 0, 2)).hasSize(2);
        DueOrder last = service.findDue(RUN, null, 0, 2).get(1);
        assertThat(service.findDue(RUN, last.paymentDueAt(), last.id(), 2)).hasSize(1);
        job.run();

        assertThat(onlineStatus(first.id())).isEqualTo("CANCELLED");
        assertThat(onlineStatus(second.id())).isEqualTo("CANCELLED");
        assertThat(onlineStatus(third.id())).isEqualTo("CANCELLED");
    }

    // ================================================================ cuộc đua và lỗi

    @Test
    void anOrderLockedByAPaymentCallbackIsSkippedThenLeftPaidWhenTheCallbackWins() throws Exception {
        Placed order = place(a, 2);
        clock.set(RUN);

        whileHeld(order.id(), () -> {
            jdbc.update("UPDATE online_orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                    order.id());
            jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                    order.orderId());
        }, job::run);

        assertThat(onlineStatus(order.id())).as("callback thanh toán thắng, job bỏ qua").isEqualTo("PAID");
        job.run();
        assertThat(onlineStatus(order.id())).isEqualTo("PAID");
        assertThat(reservationStatus(order.id())).isEqualTo("HELD");
        assertThat(salesStatus(order.orderId())).isEqualTo("PAID");
        assertThat(notices(a.accountId(), "ONLINE_ORDER_CANCELLED")).isZero();
    }

    @Test
    void anOrderLockedForAnotherReasonIsSkippedThenCancelledOnTheNextRun() throws Exception {
        Placed order = place(a, 2);
        clock.set(RUN);

        whileHeld(order.id(), () -> {
        }, job::run);

        assertThat(onlineStatus(order.id())).as("đang bị khóa: lượt này bỏ qua").isEqualTo("PENDING_PAYMENT");
        job.run();
        assertThat(onlineStatus(order.id())).isEqualTo("CANCELLED");
        assertThat(reservationStatus(order.id())).isEqualTo("RELEASED");
    }

    @Test
    void anOrderThatTurnedPaidBetweenTheScanAndTheLockIsReCheckedAndSkipped() {
        Placed order = place(a, 2);
        clock.set(RUN);
        DueOrder scanned = service.findDue(RUN, null, 0, 10).get(0);
        assertThat(scanned.id()).isEqualTo(order.id());
        jdbc.update("UPDATE online_orders SET status = 'PAID', paid_at = ? WHERE id = ?", Timestamp.from(START),
                order.id());

        assertThat(service.expire(scanned.id())).isEqualTo(Result.SKIPPED);

        assertThat(onlineStatus(order.id())).isEqualTo("PAID");
        assertThat(reservationStatus(order.id())).isEqualTo("HELD");
    }

    @Test
    void aFailingOrderRollsBackAloneAndDoesNotStopTheBatchThenRetriesNextRun() {
        Placed broken = place(a, 2);
        Placed fine = place(b, 2);
        long txn = transaction(broken.id(), "PENDING", START);
        failUpdatesOf(broken.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(fine.id())).as("đơn còn lại vẫn được xử lý").isEqualTo("CANCELLED");
        assertThat(onlineStatus(broken.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(salesStatus(broken.orderId())).as("Order rollback cùng đơn").isEqualTo("PENDING");
        assertThat(transactionStatus(txn)).isEqualTo("PENDING");
        assertThat(reservationStatus(broken.id())).isEqualTo("HELD");
        assertThat(notices(a.accountId(), "ONLINE_ORDER_CANCELLED")).isZero();

        dropFailTrigger();
        job.run();

        assertThat(onlineStatus(broken.id())).isEqualTo("CANCELLED");
        assertThat(transactionStatus(txn)).isEqualTo("CANCELLED");
        assertThat(salesStatus(broken.orderId())).isEqualTo("CANCELLED");
    }
}
