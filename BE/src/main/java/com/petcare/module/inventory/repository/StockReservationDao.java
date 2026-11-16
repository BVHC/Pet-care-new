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
 * {@code HELD}; chuyển trạng thái (nhả / xuất) thuộc task sau. Không tự mở transaction.
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
}
