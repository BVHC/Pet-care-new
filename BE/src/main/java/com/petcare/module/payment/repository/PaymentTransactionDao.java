package com.petcare.module.payment.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code payment_transactions} (V45): task 125 chỉ cần hủy giao dịch {@code PENDING} và hỏi có giao dịch
 * {@code SUCCEEDED} không; thực thể đầy đủ thuộc task thanh toán (127). Không tự mở transaction.
 */
@Repository
public class PaymentTransactionDao {

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentTransactionDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Giao dịch#5: {@code PENDING} → {@code CANCELLED} (điều kiện trạng thái nằm trong UPDATE nên lặp lại không đổi gì). */
    public int cancelPending(long onlineOrderId, Instant now) {
        return jdbc.update("""
                UPDATE payment_transactions SET status = 'CANCELLED', updated_at = :now
                WHERE online_order_id = :order AND status = 'PENDING'
                """, new MapSqlParameterSource("order", onlineOrderId).addValue("now", Timestamp.from(now)));
    }

    public boolean existsSucceeded(long onlineOrderId) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_transactions WHERE online_order_id = :order AND status = 'SUCCEEDED')
                """, new MapSqlParameterSource("order", onlineOrderId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
