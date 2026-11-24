package com.petcare.module.returns.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy cập dữ liệu ST21 trong transaction của service. */
@Repository
public class GatewayRefundDao {

    private final JdbcTemplate jdbc;

    public GatewayRefundDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> findDue(Instant cutoff, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM refunds
                WHERE method = 'GATEWAY' AND status = 'REQUESTED' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT ?
                """, Long.class, Timestamp.from(cutoff), limit);
    }

    public Optional<Long> findOnlineOrder(long refundId) {
        return jdbc.query("""
                SELECT COALESCE(refund.online_order_id, online_order.id) AS online_order_id
                FROM refunds refund
                LEFT JOIN return_requests request ON request.id = refund.return_request_id
                LEFT JOIN online_orders online_order ON online_order.order_id = request.order_id
                WHERE refund.id = ? AND refund.method = 'GATEWAY' AND refund.status = 'REQUESTED'
                  AND COALESCE(refund.online_order_id, online_order.id) IS NOT NULL
                """, (row, index) -> row.getLong("online_order_id"), refundId).stream().findFirst();
    }

    public record OnlineOrder(long orderId, long branchId, long salesBranchId, String code) {}

    public Optional<OnlineOrder> lockOnlineOrder(long onlineOrderId) {
        return jdbc.query("""
                SELECT online_order.order_id, online_order.branch_id, orders.branch_id AS sales_branch_id,
                       online_order.code
                FROM online_orders online_order
                JOIN orders ON orders.id = online_order.order_id
                WHERE online_order.id = ? FOR NO KEY UPDATE OF online_order
                """, (row, index) -> new OnlineOrder(row.getLong("order_id"), row.getLong("branch_id"),
                        row.getLong("sales_branch_id"), row.getString("code")), onlineOrderId).stream().findFirst();
    }

    public record DueRefund(long id, String code, Long returnRequestId, long branchId, long amount, int attempts) {}

    public Optional<DueRefund> lockDueRefund(long refundId, Instant now) {
        return jdbc.query("""
                SELECT id, code, return_request_id, branch_id, amount, attempts FROM refunds
                WHERE id = ? AND method = 'GATEWAY' AND status = 'REQUESTED' AND next_attempt_at <= ?
                FOR NO KEY UPDATE
                """, (row, index) -> new DueRefund(row.getLong("id"), row.getString("code"),
                        row.getObject("return_request_id", Long.class), row.getLong("branch_id"),
                        row.getLong("amount"), row.getInt("attempts")), refundId, Timestamp.from(now))
                .stream().findFirst();
    }

    public record LinkedReturn(long orderId, long branchId, String status) {}

    public Optional<LinkedReturn> lockReturn(long requestId) {
        return jdbc.query("""
                SELECT order_id, branch_id, status FROM return_requests WHERE id = ? FOR NO KEY UPDATE
                """, (row, index) -> new LinkedReturn(row.getLong("order_id"), row.getLong("branch_id"),
                        row.getString("status")), requestId).stream().findFirst();
    }

    public record OriginalPayment(String code, String gateway, String gatewayTxnId, long amount, Instant createdAt) {}

    public List<OriginalPayment> findSuccessfulPayments(long onlineOrderId) {
        return jdbc.query("""
                SELECT code, gateway, gateway_txn_id, amount, created_at FROM payment_transactions
                WHERE online_order_id = ? AND status = 'SUCCEEDED'
                ORDER BY id
                LIMIT 2
                """, (row, index) -> new OriginalPayment(row.getString("code"), row.getString("gateway"),
                        row.getString("gateway_txn_id"), row.getLong("amount"), row.getTimestamp("created_at").toInstant()),
                onlineOrderId);
    }

    public boolean totalRefundsWithinCharge(long onlineOrderId, long paymentAmount) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE(SUM(refund.amount), 0) <= ?
                FROM refunds refund
                JOIN online_orders online_order ON online_order.id = ?
                LEFT JOIN return_requests request ON request.id = refund.return_request_id
                WHERE refund.method = 'GATEWAY'
                  AND (refund.online_order_id = online_order.id OR request.order_id = online_order.order_id)
                """, Boolean.class, paymentAmount, onlineOrderId));
    }

    public void succeed(DueRefund refund, int attempts, String gatewayRefundId, Instant now) {
        jdbc.update("""
                UPDATE refunds
                SET status = 'SUCCEEDED', attempts = ?, next_attempt_at = NULL, gateway_refund_id = ?,
                    failure_reason = NULL, completed_at = ?, updated_at = ?
                WHERE id = ?
                """, attempts, gatewayRefundId, Timestamp.from(now), Timestamp.from(now), refund.id());
        if (refund.returnRequestId() != null) {
            jdbc.update("""
                    UPDATE return_requests SET status = 'REFUNDED', updated_at = ? WHERE id = ?
                    """, Timestamp.from(now), refund.returnRequestId());
        }
    }

    public void retry(long refundId, int attempts, String reason, Instant nextAttempt, Instant now) {
        jdbc.update("""
                UPDATE refunds SET attempts = ?, failure_reason = ?, next_attempt_at = ?, updated_at = ? WHERE id = ?
                """, attempts, reason, Timestamp.from(nextAttempt), Timestamp.from(now), refundId);
    }

    public void fail(long refundId, int attempts, String reason, Instant now) {
        jdbc.update("""
                UPDATE refunds
                SET status = 'FAILED', attempts = ?, failure_reason = ?, next_attempt_at = NULL, updated_at = ?
                WHERE id = ?
                """, attempts, reason, Timestamp.from(now), refundId);
    }
}
