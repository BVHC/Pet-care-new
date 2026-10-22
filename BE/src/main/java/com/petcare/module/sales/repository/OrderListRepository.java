package com.petcare.module.sales.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.entity.OrderStatus;

/**
 * Đường đọc danh sách Order (sales-v1 #2; docs/adr/0051). Chỉ ghép điều kiện đã gửi (không {@code :x IS NULL OR …},
 * như ADR-0038); luôn lọc theo chi nhánh nên mọi dạng dùng tiền tố {@code branch_id} của
 * {@code ix_orders_branch_status_pending_at}; lọc khách dùng {@code ix_orders_customer_status}. Không JOIN
 * {@code customers}: tên khách ghép theo lô qua {@code CustomerQueryApi} ở service.
 */
@Repository
public class OrderListRepository {

    /**
     * Điều kiện lọc; trường null = không gửi. {@code createdFrom} / {@code createdBefore}: nửa khoảng của ngày tạo;
     * {@code pendingBefore}: Order {@code PENDING} chốt trước mốc này (danh sách quá hạn ST13, sắp chốt cũ nhất trước).
     */
    public record Filter(long branchId, OrderStatus status, OrderSource source, Long customerId, Long visitId,
            Long boardingBookingId, Instant createdFrom, Instant createdBefore, Instant pendingBefore) {
    }

    public record Row(long orderId, String code, OrderSource source, OrderStatus status, long customerId,
            long branchId, long totalAmount, Instant createdAt, Instant pendingAt, Instant paidAt) {
    }

    private static final RowMapper<Row> MAPPER = OrderListRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public OrderListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + from(filter, params), params, Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> findPage(Filter filter, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String order = filter.pendingBefore() != null
                ? " ORDER BY o.pending_at, o.id"
                : " ORDER BY o.created_at DESC, o.id DESC";
        String sql = """
                SELECT o.id, o.code, o.source, o.status, o.customer_id, o.branch_id, o.total_amount, o.created_at,
                       o.pending_at, o.paid_at
                """ + from(filter, params) + order + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, MAPPER);
    }

    static String from(Filter filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("FROM orders o WHERE o.branch_id = :branchId");
        params.addValue("branchId", filter.branchId());
        if (filter.pendingBefore() != null) {
            sql.append(" AND o.status = 'PENDING' AND o.pending_at < :pendingBefore");
            params.addValue("pendingBefore", Timestamp.from(filter.pendingBefore()));
        }
        if (filter.status() != null) {
            sql.append(" AND o.status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.source() != null) {
            sql.append(" AND o.source = :source");
            params.addValue("source", filter.source().name());
        }
        if (filter.customerId() != null) {
            sql.append(" AND o.customer_id = :customerId");
            params.addValue("customerId", filter.customerId());
        }
        if (filter.visitId() != null) {
            sql.append(" AND o.visit_id = :visitId");
            params.addValue("visitId", filter.visitId());
        }
        if (filter.boardingBookingId() != null) {
            sql.append(" AND o.boarding_booking_id = :boardingBookingId");
            params.addValue("boardingBookingId", filter.boardingBookingId());
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND o.created_at >= :createdFrom AND o.created_at < :createdBefore");
            params.addValue("createdFrom", Timestamp.from(filter.createdFrom()))
                    .addValue("createdBefore", Timestamp.from(filter.createdBefore()));
        }
        return sql.toString();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("code"), OrderSource.valueOf(rs.getString("source")),
                OrderStatus.valueOf(rs.getString("status")), rs.getLong("customer_id"), rs.getLong("branch_id"),
                rs.getLong("total_amount"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("pending_at")), instant(rs.getTimestamp("paid_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
