package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundOutcome;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundRequest;
import com.petcare.module.payment.api.PaymentGatewayClient.RefundResult;
import com.petcare.module.returns.job.GatewayRefundJob;
import com.petcare.module.returns.service.GatewayRefundService;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.support.MutableClock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, GatewayRefundJobIT.TestBeans.class})
class GatewayRefundJobIT extends CashierItSupport {

    private static final Instant NOW = Instant.parse("2050-06-15T18:00:00Z");
    private static final long PAYMENT_AMOUNT = 50_000L;
    private static final long REFUND_AMOUNT = 25_000L;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private MutableClock clock;
    @Autowired private GatewayRefundJob job;
    @Autowired private GatewayRefundService service;
    @MockitoBean private PaymentGatewayClient gateway;

    private long branch;
    private long manager;
    private long order;
    private long onlineOrder;
    private long returnRequest;
    private long refund;
    private String refundCode;
    private String txnCode;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        branch = newBranch();
        long actor = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        long customer = newCustomer();
        long product = newPricedProduct("GOODS", false, REFUND_AMOUNT);
        order = pendingOrderSql(branch, customer, actor, LineSpec.goods(product, "Hàng IT", 2, REFUND_AMOUNT));
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("UPDATE orders SET source = 'ONLINE', status = 'PAID', paid_at = ? WHERE id = ?", at, order);
        onlineOrder = jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name,
                                           receiver_phone, ship_address, shipping_fee, payment_due_at, paid_at,
                                           shipped_at, delivered_at, carrier, tracking_code)
                VALUES (?, ?, ?, ?, 'DELIVERED', 'Khách', '0901234567', 'Địa chỉ', 0, ?, ?, ?, ?, 'GHN', 'IT')
                RETURNING id
                """, Long.class, "OL-ST21-" + order, order, customer, branch, at, at, at, at);
        txnCode = "ST21-" + onlineOrder;
        jdbc.update("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status, gateway_txn_id,
                                                  expires_at, paid_at, created_at, updated_at)
                VALUES (?, ?, 'FAKE', ?, 'SUCCEEDED', ?, ?, ?, ?, ?)
                """, txnCode, onlineOrder, PAYMENT_AMOUNT, "FAKE-" + onlineOrder, at, at, at, at);
        returnRequest = jdbc.queryForObject("""
                INSERT INTO return_requests (code, order_id, branch_id, status, reason_code, created_by,
                                             decided_by, decided_at, restock, refund_amount)
                VALUES (?, ?, ?, 'APPROVED', 'DEFECTIVE', ?, ?, ?, false, ?) RETURNING id
                """, Long.class, "TH-ST21-" + order, order, branch, actor, manager, at, REFUND_AMOUNT);
        jdbc.update("""
                INSERT INTO return_request_lines (request_id, order_line_id, quantity)
                SELECT ?, id, 1 FROM order_lines WHERE order_id = ?
                """, returnRequest, order);
        refundCode = "HT-ST21-" + order;
        refund = jdbc.queryForObject("""
                INSERT INTO refunds (code, return_request_id, branch_id, method, amount, status, next_attempt_at)
                VALUES (?, ?, ?, 'GATEWAY', ?, 'REQUESTED', ?) RETURNING id
                """, Long.class, refundCode, returnRequest, branch, REFUND_AMOUNT, at);
    }

    @AfterEach
    void cleanRefunds() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ?", manager);
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", manager);
        jdbc.update("DELETE FROM refunds WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM return_request_lines WHERE request_id = ?", returnRequest);
        jdbc.update("DELETE FROM return_requests WHERE id = ?", returnRequest);
        jdbc.update("DELETE FROM payment_transactions WHERE online_order_id = ?", onlineOrder);
        jdbc.update("DELETE FROM online_orders WHERE id = ?", onlineOrder);
    }

    @Test
    void completesRefundAndReturnOnce() {
        when(gateway.refund(any())).thenReturn(success());
        job.run();
        job.run();

        verify(gateway).refund(new RefundRequest(refundCode, txnCode, "FAKE-" + onlineOrder, REFUND_AMOUNT,
                "Hoàn tiền " + refundCode, NOW));
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT gateway_refund_id FROM refunds WHERE id = ?", String.class, refund))
                .isEqualTo("GATE-REFUND-1");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM refunds WHERE id = ?", Timestamp.class, refund))
                .isEqualTo(Timestamp.from(NOW));
        assertThat(returnStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void waitsFifteenMinutesAndReusesTheExactRequest() {
        when(gateway.refund(any())).thenReturn(new RefundResult(RefundOutcome.TEMPORARY_ERROR, null, null), success());
        service.process(refund);
        assertThat(refundStatus()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM refunds WHERE id = ?", Timestamp.class, refund))
                .isEqualTo(Timestamp.from(NOW.plusSeconds(900)));

        clock.set(NOW.plusSeconds(899));
        job.run();
        verify(gateway).refund(any());
        clock.set(NOW.plusSeconds(900));
        job.run();
        ArgumentCaptor<RefundRequest> calls = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway, times(2)).refund(calls.capture());
        assertThat(calls.getAllValues()).containsOnly(calls.getValue());
        assertThat(attempts()).isEqualTo(2);
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void stopsOnRejectionAndNotifiesManagerOnce() {
        when(gateway.refund(any())).thenReturn(new RefundResult(RefundOutcome.REJECTED, null, "test"));
        job.run();
        job.run();

        verify(gateway).refund(any());
        assertThat(refundStatus()).isEqualTo("FAILED");
        assertThat(attempts()).isEqualTo(1);
        assertThat(returnStatus()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("""
                SELECT payload ->> 'ma_hoan_tien' FROM notification_outbox
                WHERE template_code = 'REFUND_FAILED_APP' AND recipient_account_id = ?
                  AND payload ->> 'chi_nhanh' IS NOT NULL AND payload ->> 'ma_don' IS NOT NULL
                  AND payload ->> 'so_tien_hoan' = '25000'
                """, String.class, manager)).containsExactly(refundCode);
    }

    @Test
    void failsAtRetryLimitAndDoesNotNotifyEarlier() {
        when(gateway.refund(any())).thenReturn(new RefundResult(RefundOutcome.TEMPORARY_ERROR, null, "test"));
        job.run();
        clock.set(NOW.plusSeconds(900));
        job.run();
        assertThat(refundStatus()).isEqualTo("REQUESTED");
        assertThat(notices()).isZero();
        clock.set(NOW.plusSeconds(1800));
        job.run();
        assertThat(refundStatus()).isEqualTo("FAILED");
        assertThat(attempts()).isEqualTo(3);
        assertThat(notices()).isEqualTo(1);
    }

    @Test
    void refusesAmbiguousSourceWithoutCallingPaymentProvider() {
        jdbc.update("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status, gateway_txn_id,
                                                  expires_at, paid_at)
                VALUES (?, ?, 'FAKE', ?, 'SUCCEEDED', ?, ?, ?)
                """, txnCode + "-DUP", onlineOrder, PAYMENT_AMOUNT, "DUP-" + onlineOrder,
                Timestamp.from(NOW), Timestamp.from(NOW));
        job.run();
        verifyNoInteractions(gateway);
        assertThat(refundStatus()).isEqualTo("FAILED");
        assertThat(attempts()).isZero();
    }

    @Test
    void concurrentWorkersSendOneRequest() throws Exception {
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(gateway.refund(any())).thenAnswer(invocation -> {
            gatewayEntered.countDown();
            assertThat(releaseGateway.await(20, TimeUnit.SECONDS)).isTrue();
            return success();
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> service.process(refund));
            assertThat(gatewayEntered.await(20, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> service.process(refund));
            try {
                awaitLockWait(1, "online_orders", "for no key update");
            } finally {
                releaseGateway.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            releaseGateway.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        verify(gateway).refund(any());
        assertThat(refundStatus()).isEqualTo("SUCCEEDED");
    }

    private RefundResult success() {
        return new RefundResult(RefundOutcome.SUCCEEDED, "GATE-REFUND-1", null);
    }

    private String refundStatus() {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE id = ?", String.class, refund);
    }

    private String returnStatus() {
        return jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, returnRequest);
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM refunds WHERE id = ?", Integer.class, refund);
    }

    private long notices() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE template_code = 'REFUND_FAILED_APP' AND recipient_account_id = ?
                """, Long.class, manager);
    }
}
