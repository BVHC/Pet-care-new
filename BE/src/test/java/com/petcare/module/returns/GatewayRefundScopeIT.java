package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.returns.service.GatewayRefundService;
import com.petcare.module.sales.CashierItSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GatewayRefundScopeIT extends CashierItSupport {

    private static final int AMOUNT = 25_000;

    @Autowired private GatewayRefundService service;
    @MockitoBean private PaymentGatewayClient gateway;

    private long branch;
    private long otherBranch;
    private long manager;
    private long otherManager;
    private long customer;
    private long product;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        otherBranch = newBranch();
        manager = staff("BRANCH_MANAGER", branch);
        otherManager = staff("BRANCH_MANAGER", otherBranch);
        customer = newCustomer();
        product = newPricedProduct("GOODS", false, AMOUNT);
    }

    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (?, ?)", manager, otherManager);
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", manager, otherManager);
        jdbc.update("DELETE FROM refunds WHERE branch_id IN (?, ?)", branch, otherBranch);
        jdbc.update("""
                DELETE FROM return_request_lines
                WHERE request_id IN (SELECT id FROM return_requests WHERE branch_id IN (?, ?))
                """, branch, otherBranch);
        jdbc.update("DELETE FROM return_requests WHERE branch_id IN (?, ?)", branch, otherBranch);
        jdbc.update("""
                DELETE FROM payment_transactions
                WHERE online_order_id IN (SELECT id FROM online_orders WHERE branch_id IN (?, ?))
                """, branch, otherBranch);
        jdbc.update("DELETE FROM online_orders WHERE branch_id IN (?, ?)", branch, otherBranch);
    }

    @Test
    void rejectsDirectRefundWhenOnlineAndSalesBranchesDiffer() {
        PaidOnline original = paidOnline(otherBranch, branch);
        long refundId = insertRefund(null, original.onlineOrderId());

        assertScopeFailure(refundId);
    }

    @Test
    void rejectsCrossBranchReturnWithoutChangingTheLinkedRequest() {
        PaidOnline original = paidOnline(branch, branch);
        long requestId = jdbc.queryForObject("""
                INSERT INTO return_requests (code, order_id, branch_id, status, reason_code, created_by,
                                             decided_by, decided_at, restock, refund_amount)
                VALUES (?, ?, ?, 'APPROVED', 'DEFECTIVE', ?, ?, now(), false, ?) RETURNING id
                """, Long.class, code("TH"), original.orderId(), otherBranch, receptionist(otherBranch), otherManager,
                AMOUNT);
        jdbc.update("""
                INSERT INTO return_request_lines (request_id, order_line_id, quantity)
                SELECT ?, id, 1 FROM order_lines WHERE order_id = ?
                """, requestId, original.orderId());
        long refundId = insertRefund(requestId, null);

        assertScopeFailure(refundId);
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, requestId))
                .isEqualTo("APPROVED");
    }

    private PaidOnline paidOnline(long salesBranch, long onlineBranch) {
        long orderId = pendingOrderSql(salesBranch, customer, receptionist(salesBranch),
                LineSpec.goods(product, "Hàng IT", 1, AMOUNT));
        jdbc.update("UPDATE orders SET source = 'ONLINE', status = 'PAID', paid_at = now() WHERE id = ?", orderId);
        long onlineOrderId = jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name,
                                           receiver_phone, ship_address, shipping_fee, payment_due_at, paid_at,
                                           shipped_at, delivered_at, carrier, tracking_code)
                VALUES (?, ?, ?, ?, 'DELIVERED', 'Khách', '0901234567', 'Địa chỉ', 0, now(), now(), now(), now(),
                        'GHN', 'IT') RETURNING id
                """, Long.class, code("DH"), orderId, customer, onlineBranch);
        jdbc.update("""
                INSERT INTO payment_transactions (code, online_order_id, gateway, amount, status, gateway_txn_id,
                                                  expires_at, paid_at)
                VALUES (?, ?, 'FAKE', ?, 'SUCCEEDED', ?, now(), now())
                """, code("GD"), onlineOrderId, AMOUNT, code("FAKE"));
        return new PaidOnline(orderId, onlineOrderId);
    }

    private long insertRefund(Long requestId, Long onlineOrderId) {
        return jdbc.queryForObject("""
                INSERT INTO refunds (code, return_request_id, online_order_id, branch_id, method, amount, status,
                                     next_attempt_at)
                VALUES (?, ?, ?, ?, 'GATEWAY', ?, 'REQUESTED', now() - interval '1 minute') RETURNING id
                """, Long.class, code("HT"), requestId, onlineOrderId, branch, AMOUNT);
    }

    private void assertScopeFailure(long refundId) {
        service.process(refundId);

        verifyNoInteractions(gateway);
        assertThat(jdbc.queryForMap("""
                SELECT status, gateway_refund_id, completed_at FROM refunds WHERE id = ?
                """, refundId)).containsEntry("status", "FAILED")
                .containsEntry("gateway_refund_id", null).containsEntry("completed_at", null);
        assertThat(jdbc.queryForObject("SELECT attempts FROM refunds WHERE id = ?", Integer.class, refundId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE template_code = 'REFUND_FAILED_APP' AND recipient_account_id IN (?, ?)
                """, Long.class, manager, otherManager)).isZero();
    }

    private static String code(String prefix) {
        return prefix + "-IT-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private record PaidOnline(long orderId, long onlineOrderId) {}
}
