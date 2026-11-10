package com.petcare.module.visit.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lượt khám đã hoàn tất, cho I-6 (07 §11). Chỉ đọc; dịch vụ của lượt nằm ở Order nên do sales trả (VisitOrderApi). */
@Repository
public class VisitUsageRepository {

    public record CompletedVisit(Long visitId, Long petId, Instant completedAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    public VisitUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Visit {@code COMPLETED} của một thú. */
    public List<CompletedVisit> completedOfPet(Long petId) {
        return jdbc.query("""
                SELECT id, pet_id, completed_at FROM visits
                WHERE pet_id = :petId AND status = 'COMPLETED' AND completed_at IS NOT NULL
                """, new MapSqlParameterSource("petId", petId), (rs, n) -> row(rs));
    }

    /** Visit {@code COMPLETED} có {@code completed_at >= from}, toàn chuỗi. */
    public List<CompletedVisit> completedSince(Instant from) {
        return jdbc.query("""
                SELECT id, pet_id, completed_at FROM visits
                WHERE status = 'COMPLETED' AND completed_at >= :from
                """, new MapSqlParameterSource("from", java.sql.Timestamp.from(from)), (rs, n) -> row(rs));
    }

    private static CompletedVisit row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CompletedVisit(rs.getLong("id"), rs.getLong("pet_id"),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant());
    }
}
