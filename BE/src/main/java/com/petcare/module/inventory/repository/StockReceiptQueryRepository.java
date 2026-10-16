package com.petcare.module.inventory.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.inventory.dto.StockReceiptSummaryResponse;
import com.petcare.module.inventory.entity.StockReceiptStatus;

/**
 * Đường đọc danh sách phiếu nhập (inventory-v1 #5; docs/adr/0042). Chỉ ghép điều kiện đã gửi (không {@code :x IS NULL
 * OR …}, như ADR-0038); luôn lọc theo chi nhánh của người gọi; dùng {@code ix_stock_receipts_branch_date}.
 */
@Repository
public class StockReceiptQueryRepository {

    /** Điều kiện lọc; trường null = không gửi. */
    public record Filter(long branchId, StockReceiptStatus status, LocalDate from, LocalDate to, Long supplierId) {
    }

    private static final RowMapper<StockReceiptSummaryResponse> MAPPER = StockReceiptQueryRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public StockReceiptQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + from(filter, params), params, Long.class);
        return total == null ? 0 : total;
    }

    public List<StockReceiptSummaryResponse> findPage(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
                SELECT r.id, r.code, s.name AS supplier_name, r.receipt_date, r.status,
                       (SELECT count(*) FROM stock_receipt_lines l WHERE l.stock_receipt_id = r.id) AS line_count,
                       (SELECT COALESCE(SUM(l.quantity::bigint * l.unit_cost), 0)
                        FROM stock_receipt_lines l WHERE l.stock_receipt_id = r.id) AS total_cost
                """ + from(filter, params) + " ORDER BY r.receipt_date DESC, r.id DESC LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, MAPPER);
    }

    private static String from(Filter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(
                "FROM stock_receipts r JOIN suppliers s ON s.id = r.supplier_id WHERE r.branch_id = :branchId");
        params.addValue("branchId", filter.branchId());
        if (filter.status() != null) {
            sql.append(" AND r.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.from() != null) {
            sql.append(" AND r.receipt_date >= :from");
            params.addValue("from", filter.from());
        }
        if (filter.to() != null) {
            sql.append(" AND r.receipt_date <= :to");
            params.addValue("to", filter.to());
        }
        if (filter.supplierId() != null) {
            sql.append(" AND r.supplier_id = :supplierId");
            params.addValue("supplierId", filter.supplierId());
        }
        return sql.toString();
    }

    private static StockReceiptSummaryResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new StockReceiptSummaryResponse(rs.getLong("id"), rs.getString("code"), rs.getString("supplier_name"),
                rs.getDate("receipt_date").toLocalDate(), StockReceiptStatus.valueOf(rs.getString("status")),
                rs.getInt("line_count"), rs.getLong("total_cost"));
    }
}
