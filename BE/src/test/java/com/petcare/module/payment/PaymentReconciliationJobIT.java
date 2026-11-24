package com.petcare.module.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayStatus;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayTransaction;
import com.petcare.module.payment.gateway.FakeGatewayClient;
import com.petcare.module.payment.job.PaymentReconciliationJob;
import com.petcare.module.payment.service.PaymentReconciliationService;

/** ST14 trên database Flyway thật và báo cáo do FakeGatewayClient cấp. */
@SpringBootTest(properties = "app.jobs.payment-reconciliation.cron=-")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PaymentReconciliationJobIT.TestClock.class})
class PaymentReconciliationJobIT {
    private static final LocalDate REPORT_DATE = LocalDate.of(2035, 6, 8);
    private static final Instant START = Instant.parse("2035-06-07T17:00:00Z");
    private static final Instant END = Instant.parse("2035-06-08T17:00:00Z");
    private static final Instant RUN = Instant.parse("2035-06-08T19:05:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        Clock clockForReconciliation() {
            return Clock.fixed(RUN, ZoneOffset.UTC);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentReconciliationJob job;

    @Autowired
    private PaymentReconciliationService service;

    @MockitoSpyBean
    private FakeGatewayClient gateway;

    @Test
    void reconcilesThePreviousVietnamDayWithoutChangingPaymentsOrOrders() {
        long order = createOnlineOrder();
        long missingGateway = local(order, "missing-gateway", "FAKE", "FAILED", 100L, START);
        long amountDiff = local(order, "amount-diff", "FAKE", "FAILED", 120L, START.plusSeconds(10));
        long statusDiff = local(order, "status-diff", "FAKE", "FAILED", 130L, START.plusSeconds(20));
        local(order, "matched", "FAKE", "FAILED", 140L, END.minusSeconds(1));
        local(order, "before-day", "FAKE", "FAILED", 150L, START.minusSeconds(1));
        local(order, "after-day", "FAKE", "FAILED", 160L, END);
        local(order, "other-gateway", "VNPAY", "FAILED", 170L, START);
        gateway.recordSettlement(REPORT_DATE, new GatewayTransaction("missing-local", "GW-NO-LOCAL", 90L,
                GatewayStatus.SUCCEEDED));
        gateway.recordSettlement(REPORT_DATE, new GatewayTransaction("amount-diff", "GW-AMOUNT", 121L,
                GatewayStatus.SUCCEEDED));
        gateway.recordSettlement(REPORT_DATE, new GatewayTransaction("status-diff", "GW-STATUS", 130L,
                GatewayStatus.SUCCEEDED));
        gateway.recordSettlement(REPORT_DATE, new GatewayTransaction("matched", "GW-MATCHED", 140L,
                GatewayStatus.FAILED));
        List<Map<String, Object>> paymentsBefore = jdbc.queryForList("""
                SELECT * FROM payment_transactions WHERE online_order_id = ? ORDER BY id
                """, order);
        Map<String, Object> onlineBefore = jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?", order);
        Map<String, Object> salesBefore = jdbc.queryForMap("""
                SELECT s.* FROM orders s JOIN online_orders o ON o.order_id = s.id WHERE o.id = ?
                """, order);

        job.run();
        job.run();

        Map<String, Object> run = runRow(REPORT_DATE);
        assertThat(run).containsEntry("status", "MISMATCHED").containsEntry("txn_count", 5)
                .containsEntry("mismatch_count", 4);
        assertThat(((Timestamp) run.get("finished_at")).toInstant()).isEqualTo(RUN);
        assertThat(jdbc.queryForList("""
                SELECT kind, transaction_id, gateway_ref, our_amount, gateway_amount
                FROM reconciliation_items WHERE reconciliation_id = ?
                """, run.get("id")))
                .extracting(item -> item.get("kind"), item -> item.get("transaction_id"),
                        item -> item.get("gateway_ref"), item -> item.get("our_amount"),
                        item -> item.get("gateway_amount"))
                .containsExactlyInAnyOrder(
                        tuple("MISSING_LOCAL", null, "GW-NO-LOCAL", null, 90L),
                        tuple("MISSING_GATEWAY", missingGateway, "missing-gateway", 100L, null),
                        tuple("AMOUNT_DIFF", amountDiff, "GW-AMOUNT", 120L, 121L),
                        tuple("STATUS_DIFF", statusDiff, "GW-STATUS", 130L, 130L));
        assertThat(jdbc.queryForList("SELECT * FROM payment_transactions WHERE online_order_id = ? ORDER BY id",
                order)).isEqualTo(paymentsBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM online_orders WHERE id = ?", order)).isEqualTo(onlineBefore);
        assertThat(jdbc.queryForMap("""
                SELECT s.* FROM orders s JOIN online_orders o ON o.order_id = s.id WHERE o.id = ?
                """, order)).isEqualTo(salesBefore);
        verify(gateway).fetchSettlementReport(REPORT_DATE);
    }

    @Test
    void aProviderFailureRollsBackTheClaimAndTheDateCanBeRetried() {
        LocalDate date = REPORT_DATE.plusDays(2);
        doThrow(new UnsupportedOperationException("no report")).doCallRealMethod()
                .when(gateway).fetchSettlementReport(date);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reconcile(date))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reconciliations WHERE recon_date = ?",
                Long.class, date)).isZero();

        assertThat(service.reconcile(date)).isTrue();
        assertThat(runRow(date)).containsEntry("status", "MATCHED").containsEntry("txn_count", 0)
                .containsEntry("mismatch_count", 0);
        verify(gateway, times(2)).fetchSettlementReport(date);
    }

    @Test
    void concurrentWorkersFetchAndCommitTheDayOnlyOnce() throws Exception {
        LocalDate date = REPORT_DATE.plusDays(3);
        gateway.recordSettlement(date, new GatewayTransaction("race", "GW-RACE", 100L, GatewayStatus.SUCCEEDED));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> start.await(10, TimeUnit.SECONDS) && service.reconcile(date));
            Future<Boolean> second = workers.submit(() -> start.await(10, TimeUnit.SECONDS) && service.reconcile(date));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            workers.shutdownNow();
        }

        Map<String, Object> run = runRow(date);
        assertThat(run).containsEntry("status", "MISMATCHED").containsEntry("txn_count", 1)
                .containsEntry("mismatch_count", 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_items WHERE reconciliation_id = ?",
                Long.class, run.get("id"))).isEqualTo(1L);
        verify(gateway).fetchSettlementReport(date);
    }

    private Map<String, Object> runRow(LocalDate date) {
        return jdbc.queryForMap("SELECT * FROM payment_reconciliations WHERE recon_date = ?", date);
    }

    private long local(long order, String code, String provider, String status, long amount, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status,
                                                  expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, code, order, provider, amount, status, Timestamp.from(createdAt.plusSeconds(900)),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private long createOnlineOrder() {
        long branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES ('ST14 IT', 'Test address', '0900000000', 10.0, 106.0, 'DRAFT') RETURNING id
                """, Long.class);
        long account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES ('st14-it@example.com', 'unused', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class);
        long customer = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel)
                VALUES (?, 'ST14 IT', 'ONLINE') RETURNING id
                """, Long.class, account);
        long salesOrder = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, total_amount, created_by, pending_at)
                VALUES ('ST14-IT-SALES', 'ONLINE', ?, ?, 'PENDING', 100, ?, ?) RETURNING id
                """, Long.class, customer, branch, account, Timestamp.from(START));
        return jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name,
                                           receiver_phone, ship_address, shipping_fee, payment_due_at)
                VALUES ('ST14-IT-ONLINE', ?, ?, ?, 'PENDING_PAYMENT', 'Test', '0900000000', 'Test address', 0, ?)
                RETURNING id
                """, Long.class, salesOrder, customer, branch, Timestamp.from(END.plusSeconds(3600)));
    }
}
