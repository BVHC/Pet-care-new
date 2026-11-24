package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.platform.config.TimeConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefundConfirmScopeIT extends CashierItSupport {

    private static final int AMOUNT = 25_000;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long customer;
    private long shift;
    private String staffToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        customer = newCustomer();
        shift = openShiftSql(branch, receptionist, false, LocalDate.now(TimeConfig.BUSINESS_ZONE),
                Instant.now().minusSeconds(60));
        staffToken = token(receptionist);
        managerToken = token(manager);
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.update("""
                DELETE FROM audit_logs WHERE entity_type = 'refunds' AND entity_id IN
                  (SELECT id FROM refunds WHERE branch_id IN (?, ?))
                """, branch, otherBranch);
        jdbc.update("DELETE FROM refunds WHERE branch_id IN (?, ?)", branch, otherBranch);
        jdbc.update("DELETE FROM return_requests WHERE branch_id IN (?, ?)", branch, otherBranch);
        jdbc.update("DELETE FROM online_orders WHERE branch_id IN (?, ?)", branch, otherBranch);
    }

    @Test
    void counterRefundRequiresBothRequestAndOriginalOrderToShareItsBranch() {
        long foreignOrder = counterOrder(otherBranch);
        long foreignRequest = approvedReturn(counterOrder(branch), otherBranch);
        long falselyLocalRequest = approvedReturn(foreignOrder, branch);
        for (long requestId : new long[] {foreignRequest, falselyLocalRequest}) {
            long refundId = counterRefund(requestId);
            assertScopeDenied(confirm(refundId, staffToken, Map.of()));
            assertUnmodified(refundId, "REQUESTED");
            assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, requestId))
                    .isEqualTo("APPROVED");
        }
        assertThat(orderStatus(foreignOrder)).isEqualTo("PAID");
    }

    @Test
    void directGatewayRefundRequiresBothOnlineAndSalesOrderToShareItsBranch() {
        for (long[] branches : new long[][] {{branch, otherBranch}, {otherBranch, branch}}) {
            long onlineOrder = onlineOrder(branches[0], branches[1]);
            long refundId = jdbc.queryForObject("""
                    INSERT INTO refunds (code, online_order_id, branch_id, method, amount, status)
                    VALUES (?, ?, ?, 'GATEWAY', ?, 'FAILED') RETURNING id
                    """, Long.class, code("HT"), onlineOrder, branch, AMOUNT);
            assertScopeDenied(confirm(refundId, managerToken, Map.of("note", "Đã hoàn ngoài hệ thống")));
            assertUnmodified(refundId, "FAILED");
        }
    }

    @Test
    void closingARefundOnlyShiftRetainsTheSignedNetCashTotal() {
        long original = counterOrder(branch);
        long refundId = counterRefund(approvedReturn(original, branch));
        data(confirm(refundId, staffToken, Map.of()), HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE cashier_shift_id = ?", Long.class, shift))
                .isZero();

        Map<String, Object> closed = data(call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/close", staffToken,
                Map.of("countedCash", 0)), HttpStatus.OK);
        assertThat(closed).containsEntry("expectedCash", -AMOUNT).containsEntry("expectedTransfer", 0);
        assertThat(closed.get("difference")).isNull();
        assertThat(shiftRow(shift)).containsEntry("expected_cash", -(long) AMOUNT);
        assertThat(orderStatus(original)).isEqualTo("PAID");
    }

    private long counterOrder(long orderBranch) {
        long previousCashier = receptionist(orderBranch);
        long product = newPricedProduct("GOODS", false, AMOUNT);
        long order = pendingOrderSql(orderBranch, customer, previousCashier, LineSpec.goods(product, "Hàng IT", 1, AMOUNT));
        Instant paidAt = Instant.now().minusSeconds(172_800);
        long previousShift = openShiftSql(orderBranch, previousCashier, false,
                paidAt.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(), paidAt.minusSeconds(60));
        long payment = paymentSql(orderBranch, customer, previousShift, "CASH", AMOUNT, previousCashier);
        jdbc.update("UPDATE payments SET paid_at = ? WHERE id = ?", Timestamp.from(paidAt), payment);
        jdbc.update("UPDATE orders SET status = 'PAID', payment_id = ?, paid_at = ? WHERE id = ?",
                payment, Timestamp.from(paidAt), order);
        return order;
    }

    private long approvedReturn(long order, long requestBranch) {
        return jdbc.queryForObject("""
                INSERT INTO return_requests (code, order_id, branch_id, status, reason_code, created_by,
                                             decided_by, decided_at, restock, refund_amount)
                VALUES (?, ?, ?, 'APPROVED', 'DEFECTIVE', ?, ?, now(), false, ?) RETURNING id
                """, Long.class, code("TH"), order, requestBranch, receptionist, manager, AMOUNT);
    }

    private long counterRefund(long requestId) {
        return jdbc.queryForObject("""
                INSERT INTO refunds (code, return_request_id, branch_id, method, amount, status)
                VALUES (?, ?, ?, 'CASH', ?, 'REQUESTED') RETURNING id
                """, Long.class, code("HT"), requestId, branch, AMOUNT);
    }

    private long onlineOrder(long orderBranch, long shippingBranch) {
        long order = pendingOrderSql(orderBranch, customer, receptionist);
        jdbc.update("UPDATE orders SET source = 'ONLINE', status = 'PAID', paid_at = now() WHERE id = ?", order);
        return jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name,
                                           receiver_phone, ship_address, shipping_fee, payment_due_at, paid_at)
                VALUES (?, ?, ?, ?, 'PAID', 'Khách', '0901234567', 'Địa chỉ', 0, now(), now()) RETURNING id
                """, Long.class, code("DH"), order, customer, shippingBranch);
    }

    private void assertUnmodified(long refundId, String status) {
        assertThat(jdbc.queryForMap("""
                SELECT status, confirmed_by, cashier_shift_id, completed_at FROM refunds WHERE id = ?
                """, refundId)).containsEntry("status", status).containsEntry("confirmed_by", null)
                .containsEntry("cashier_shift_id", null).containsEntry("completed_at", null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE entity_type = 'refunds' AND entity_id = ?
                """, Long.class, refundId)).isZero();
    }

    private static void assertScopeDenied(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    private ResponseEntity<Map<String, Object>> confirm(long id, String token, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/refunds/" + id + "/confirm", token, body);
    }

    private static String code(String prefix) {
        return prefix + "-IT-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
