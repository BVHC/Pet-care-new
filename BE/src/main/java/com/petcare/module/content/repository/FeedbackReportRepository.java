package com.petcare.module.content.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.content.api.FeedbackReportApi.FeedbackStatRow;

/**
 * Gộp feedback theo chi nhánh cho báo cáo (BR-BC-03 (6); docs/adr/0087). Chỉ đọc. {@code branchId} null = toàn chuỗi,
 * gồm nhóm feedback không gắn chi nhánh ({@code branch_id IS NULL}, BR-DG-03); điều kiện chi nhánh chỉ ghép khi có.
 */
@Repository
public class FeedbackReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeedbackReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedbackStatRow> stats(Long branchId, Instant from, Instant toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(toExclusive));
        String branch = "";
        if (branchId != null) {
            branch = " AND branch_id = :branchId";
            params.addValue("branchId", branchId);
        }
        return jdbc.query("""
                SELECT branch_id, count(*) AS cnt, round(avg(rating)::numeric, 2) AS average
                FROM feedbacks
                WHERE created_at >= :from AND created_at < :to""" + branch + """
                 GROUP BY branch_id ORDER BY branch_id NULLS LAST
                """, params, (rs, i) -> new FeedbackStatRow(rs.getObject("branch_id", Long.class),
                Math.toIntExact(rs.getLong("cnt")), rs.getBigDecimal("average")));
    }
}
