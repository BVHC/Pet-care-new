package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ReturnRefundFsmIT.TestBeans.class})
class ReturnRefundFsmIT extends CashierItSupport {

    private static final Instant NOW = Instant.parse("2050-06-15T18:00:00Z");
    private static final String RETURNS = "/api/return-requests";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long receptionist;
    private long manager;
    private long order;
    private long line;
    private long shift;
    private String receptionistToken;
    private String managerToken;

    @BeforeEach
    void createPaidOrder() {
        branch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        long customer = newCustomer();
        long goods = newPricedProduct("GOODS", false, 25_000);
        order = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hàng trả", 2, 25_000));
        line = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, order);
        shift = openShiftSql(branch, receptionist, false, NOW.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(),
                NOW.minusSeconds(60));
        long payment = paymentSql(branch, customer, shift, "CASH", orderTotal(order), receptionist);
        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ?, payment_id = ? WHERE id = ?",
                Timestamp.from(NOW), payment, order);
        receptionistToken = token(receptionist);
        managerToken = token(manager);
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (?, ?)", receptionist, manager);
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", receptionist, manager);
        jdbc.update("""
                DELETE FROM audit_logs WHERE
                    (entity_type = 'return_requests' AND entity_id IN
                        (SELECT id FROM return_requests WHERE order_id = ?))
                    OR (entity_type = 'refunds' AND entity_id IN (
                        SELECT id FROM refunds WHERE branch_id = ?))
                """, order, branch);
        jdbc.update("DELETE FROM refunds WHERE branch_id = ?", branch);
        jdbc.update("""
                DELETE FROM return_request_lines
                WHERE request_id IN (SELECT id FROM return_requests WHERE order_id = ?)
                """, order);
        jdbc.update("DELETE FROM return_requests WHERE order_id = ?", order);
    }

    @Test
    void rejectedQuantityIsReleasedAndRefundedQuantityRemainsClaimed() {
        long rejected = createRequest(2);
        assertRule(create(1), "BR-BH-13");
        assertThat(data(reject(rejected, "Sai yêu cầu"), HttpStatus.OK)).containsEntry("status", "REJECTED");
        assertInvalidEdge(reject(rejected, "Không được ghi đè"));

        long refunded = createRequest(1);
        long refund = seedApprovedCashRefund(refunded);
        assertThat(data(confirm(refund), HttpStatus.OK)).containsEntry("status", "SUCCEEDED");
        assertInvalidEdge(confirm(refund));
        assertInvalidEdge(reject(refunded, "Hoàn rồi"));

        assertRule(create(2), "BR-BH-13");
        long pending = createRequest(1);
        assertThat(jdbc.queryForList("""
                SELECT id FROM return_requests WHERE order_id = ? ORDER BY id
                """, Long.class, order)).containsExactly(rejected, refunded, pending);
        assertThat(jdbc.queryForList("""
                SELECT status FROM return_requests WHERE order_id = ? ORDER BY id
                """, String.class, order)).containsExactly("REJECTED", "REFUNDED", "PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("""
                SELECT cashier_shift_id FROM refunds WHERE id = ? AND return_request_id = ? AND status = 'SUCCEEDED'
                """, Long.class, refund, refunded)).isEqualTo(shift);
        assertThat(jdbc.queryForList("""
                SELECT reason FROM audit_logs WHERE entity_type = 'return_requests' AND entity_id = ?
                    AND action = 'RETURN_REQUEST_REJECTED'
                """, String.class, rejected)).containsExactly("Sai yêu cầu");
        assertThat(jdbc.queryForList("""
                SELECT after_data ->> 'status' FROM audit_logs WHERE entity_type = 'refunds' AND entity_id = ?
                    AND action = 'REFUND_CONFIRMED'
                """, String.class, refund)).containsExactly("SUCCEEDED");
    }

    private long createRequest(int quantity) {
        return number(data(create(quantity), HttpStatus.CREATED).get("requestId"));
    }

    private ResponseEntity<Map<String, Object>> create(int quantity) {
        return call(HttpMethod.POST, RETURNS, receptionistToken, Map.of("orderId", order,
                "reasonCode", "DEFECTIVE", "lines", List.of(Map.of("orderLineId", line, "quantity", quantity))));
    }

    private ResponseEntity<Map<String, Object>> reject(long request, String reason) {
        return call(HttpMethod.POST, RETURNS + "/" + request + "/reject", managerToken, Map.of("reason", reason));
    }

    private ResponseEntity<Map<String, Object>> confirm(long refund) {
        return call(HttpMethod.POST, "/api/refunds/" + refund + "/confirm", receptionistToken, Map.of());
    }

    private long seedApprovedCashRefund(long request) {
        // Fixture for the approval edge. The real approval/stock migration is not part of this test.
        assertThat(jdbc.update("""
                UPDATE return_requests SET status = 'APPROVED', decided_by = ?, decided_at = ?, updated_at = ?,
                    restock = false, refund_amount = 25000 WHERE id = ? AND status = 'PENDING'
                """, manager, Timestamp.from(NOW), Timestamp.from(NOW), request)).isEqualTo(1);
        return jdbc.queryForObject("""
                INSERT INTO refunds (code, return_request_id, branch_id, method, amount, status)
                VALUES (?, ?, ?, 'CASH', 25000, 'REQUESTED') RETURNING id
                """, Long.class, "HT-FSM-" + request, request, branch);
    }

    private static void assertInvalidEdge(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION");
    }
}
