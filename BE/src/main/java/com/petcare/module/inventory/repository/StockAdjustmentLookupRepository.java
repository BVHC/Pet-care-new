package com.petcare.module.inventory.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tra cứu theo lô cho màn hình phiếu điều chỉnh (một câu cho cả trang, không N+1): sản phẩm / số lô của lô, và số dư sau
 * của từng dòng lấy từ {@code stock_movements} nguồn phiếu. Chỉ đọc.
 */
@Repository
public class StockAdjustmentLookupRepository {

    /** Lô thuộc sản phẩm nào, số lô gì. */
    public record LotInfo(Long productId, String lotNumber) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public StockAdjustmentLookupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, LotInfo> lotInfo(Collection<Long> lotIds) {
        Map<Long, LotInfo> result = new HashMap<>();
        if (lotIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT l.id, i.product_id, l.lot_number
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id WHERE l.id IN (:ids)
                """, new MapSqlParameterSource("ids", lotIds),
                rs -> {
                    result.put(rs.getLong("id"), new LotInfo(rs.getLong("product_id"), rs.getString("lot_number")));
                });
        return result;
    }

    /** {@code adjustmentId → (lotId → số dư sau)}; mỗi lô có đúng một movement cho một phiếu. */
    public Map<Long, Map<Long, Integer>> balancesAfter(Collection<Long> adjustmentIds) {
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        if (adjustmentIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT source_id, stock_lot_id, balance_after FROM stock_movements
                WHERE source_type = 'stock_adjustments' AND source_id IN (:ids)
                """, new MapSqlParameterSource("ids", adjustmentIds), rs -> {
                    result.computeIfAbsent(rs.getLong("source_id"), k -> new HashMap<>())
                            .put(rs.getLong("stock_lot_id"), rs.getInt("balance_after"));
                });
        return result;
    }
}
