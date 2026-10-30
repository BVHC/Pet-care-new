package com.petcare.module.appointment.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.appointment.api.AppointmentReportApi.NoShowRow;

/**
 * Báo cáo NO_SHOW và hủy muộn (BR-BC-03 (3); docs/adr/0087). Chỉ đọc, một câu gộp nhóm. Viết thay BE-1 bởi BE-2 ngày
 * 30/10 (06 §6). Lịch đến hạn = {@code slot_date} trong kỳ (cả hai đầu), trừ lịch phòng khám hủy
 * ({@code cancel_source = 'CLINIC'}); điều kiện chi nhánh chỉ ghép khi có.
 */
@Repository
public class AppointmentReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AppointmentReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NoShowRow> noShowAndLateCancel(Long branchId, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);
        String branch = "";
        if (branchId != null) {
            branch = " AND a.branch_id = :branchId";
            params.addValue("branchId", branchId);
        }
        return jdbc.query("""
                SELECT a.branch_id, count(*) AS due,
                       count(*) FILTER (WHERE a.status = 'NO_SHOW') AS no_show,
                       count(*) FILTER (WHERE a.status = 'CANCELLED' AND a.late_cancel) AS late_cancel
                FROM appointments a
                WHERE a.slot_date BETWEEN :from AND :to
                  AND a.cancel_source IS DISTINCT FROM 'CLINIC'""" + branch + """
                 GROUP BY a.branch_id ORDER BY a.branch_id
                """, params, (rs, i) -> new NoShowRow(rs.getLong("branch_id"),
                Math.toIntExact(rs.getLong("due")), Math.toIntExact(rs.getLong("no_show")),
                Math.toIntExact(rs.getLong("late_cancel"))));
    }
}
