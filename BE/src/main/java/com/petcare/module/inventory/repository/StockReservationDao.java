package com.petcare.module.inventory.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code stock_reservations} (BR-BH-09, V44): phần hàng đang giữ cho đơn online. Chỉ đọc tổng {@code HELD} và chèn dòng
 * {@code HELD}; nhả ({@code HELD} → {@code RELEASED}, task 125); xuất thuộc task sau. Không tự mở transaction.
 */
@Repository
public class StockReservationDao {

    private final NamedParameterJdbcTemplate jdbc;

    public StockReservationDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tổng {@code HELD} theo sản phẩm tại chi nhánh; sản phẩm không có dòng giữ vắng mặt trong map. */
    public Map<Long, Integer> heldQuantities(long branchId, Collection<Long> productIds) {
        Map<Long, Integer> held = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return held;
        }
        jdbc.query("""
                SELECT r.product_id AS product_id, SUM(r.quantity) AS held
                FROM stock_reservations r
                WHERE r.branch_id = :branchId AND r.product_id IN (:productIds) AND r.status = 'HELD'
                GROUP BY r.product_id
                """, new MapSqlParameterSource("branchId", branchId).addValue("productIds", productIds), rs -> {
                    held.put(rs.getLong("product_id"), Math.toIntExact(Math.min(rs.getLong("held"),
                            Integer.MAX_VALUE)));
                });
        return held;
    }

    public void insertHeld(long onlineOrderId, long branchId, long productId, int quantity, Instant now) {
        jdbc.update("""
                INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status, created_at,
                                                updated_at)
                VALUES (:order, :branch, :product, :quantity, 'HELD', :now, :now)
                """, new MapSqlParameterSource("order", onlineOrderId).addValue("branch", branchId)
                .addValue("product", productId).addValue("quantity", quantity).addValue("now", Timestamp.from(now)));
    }

    /** Nhả phần giữ của một đơn online: {@code HELD} → {@code RELEASED}; trả số dòng đã nhả (0 = không còn gì giữ). */
    public int releaseHeld(long onlineOrderId, Instant now) {
        return jdbc.update("""
                UPDATE stock_reservations SET status = 'RELEASED', updated_at = :now
                WHERE online_order_id = :order AND status = 'HELD'
                """, new MapSqlParameterSource("order", onlineOrderId).addValue("now", Timestamp.from(now)));
    }
}
