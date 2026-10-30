package com.petcare.module.sales.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.api.SalesReportApi.LostRevenueRow;

/**
 * Ba câu gộp nhóm của báo cáo bán hàng (BR-BC-02, BR-BC-03 (2); {@code SalesReportApi}; docs/adr/0087). Chỉ đọc, không
 * khóa. Kỳ là nửa mở {@code [from, toExclusive)} đã đổi sang {@link Instant}; {@code branchId} null = toàn chuỗi (điều
 * kiện chỉ được ghép khi có, ADR-0038).
 */
@Repository
public class SalesReportRepository {

    public record RevenueAgg(long branchId, String source, int orderCount, long amount) {
    }

    public record ServiceAgg(long branchId, long serviceId, int count) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SalesReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Order {@code PAID} có {@code paid_at} trong kỳ, theo chi nhánh của Order và nguồn. */
    public List<RevenueAgg> revenue(Long branchId, Instant from, Instant toExclusive) {
        MapSqlParameterSource params = period(branchId, from, toExclusive);
        return jdbc.query("""
                SELECT o.branch_id, o.source, count(*) AS order_count, COALESCE(sum(o.total_amount), 0) AS amount
                FROM orders o
                WHERE o.status = 'PAID' AND o.paid_at >= :from AND o.paid_at < :to""" + branch(branchId) + """
                 GROUP BY o.branch_id, o.source ORDER BY o.branch_id, o.source
                """, params, (rs, i) -> new RevenueAgg(rs.getLong("branch_id"), rs.getString("source"),
                Math.toIntExact(rs.getLong("order_count")), rs.getLong("amount")));
    }

    /** Hủy do khách không trả ({@code cancel_type = 'UNPAID'}, BR-BH-05); hủy phiên trả thú không tính (BR-LT-09). */
    public List<LostRevenueRow> lostRevenue(Long branchId, Instant from, Instant toExclusive) {
        MapSqlParameterSource params = period(branchId, from, toExclusive);
        return jdbc.query("""
                SELECT o.id, o.code, o.branch_id, o.source, o.total_amount, o.cancel_reason, o.cancelled_at
                FROM orders o
                WHERE o.status = 'CANCELLED' AND o.cancel_type = 'UNPAID'
                  AND o.cancelled_at >= :from AND o.cancelled_at < :to""" + branch(branchId) + """
                 ORDER BY o.cancelled_at, o.id
                """, params, (rs, i) -> new LostRevenueRow(rs.getLong("id"), rs.getString("code"),
                rs.getLong("branch_id"), OrderSource.valueOf(rs.getString("source")), rs.getLong("total_amount"),
                rs.getString("cancel_reason"), rs.getObject("cancelled_at", java.time.OffsetDateTime.class).toInstant()));
    }

    /** Số dòng {@code SERVICE} của Order VISIT có {@code pending_at} trong kỳ (06 Q3), mọi trạng thái Order. */
    public List<ServiceAgg> serviceCounts(Long branchId, Instant from, Instant toExclusive) {
        MapSqlParameterSource params = period(branchId, from, toExclusive);
        return jdbc.query("""
                SELECT o.branch_id, l.service_id, count(*) AS cnt
                FROM orders o JOIN order_lines l ON l.order_id = o.id AND l.line_type = 'SERVICE'
                WHERE o.source = 'VISIT' AND o.pending_at >= :from AND o.pending_at < :to""" + branch(branchId) + """
                 GROUP BY o.branch_id, l.service_id ORDER BY o.branch_id, l.service_id
                """, params, (rs, i) -> new ServiceAgg(rs.getLong("branch_id"), rs.getLong("service_id"),
                Math.toIntExact(rs.getLong("cnt"))));
    }

    private static String branch(Long branchId) {
        return branchId == null ? "" : " AND o.branch_id = :branchId";
    }

    private static MapSqlParameterSource period(Long branchId, Instant from, Instant toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(toExclusive));
        if (branchId != null) {
            params.addValue("branchId", branchId);
        }
        return params;
    }
}
