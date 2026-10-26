package com.petcare.module.care.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ghi bảng {@code care_tasks} bằng SQL (chưa có entity: Care Task đầy đủ là T39, docs/adr/0074). Chỉ gọi từ
 * {@code CareTaskService} trong transaction của bên gọi.
 */
@Repository
public class CareTaskWriteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CareTaskWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Care Task#5: task {@code VACCINE_DUE} / {@code VACCINE_OVERDUE} còn {@code OPEN} của các mũi đã được tiêm lại →
     * {@code CANCELLED}. Điều kiện {@code status = 'OPEN'} nằm trong câu UPDATE nên task đã {@code DONE} /
     * {@code CANCELLED} không bị đổi. {@code updated_at} đặt từ {@code Clock} (docs/adr/0015). Bên gọi không truyền
     * tập rỗng.
     */
    public int cancelOpenVaccineTasks(Collection<Long> vaccinationIds, String reason, Instant now) {
        return jdbc.update("""
                UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = :reason, updated_at = :now
                WHERE status = 'OPEN' AND task_type IN ('VACCINE_DUE', 'VACCINE_OVERDUE')
                  AND vaccination_id IN (:ids)
                """, new MapSqlParameterSource(Map.of("reason", reason, "now", Timestamp.from(now), "ids", vaccinationIds)));
    }
}
