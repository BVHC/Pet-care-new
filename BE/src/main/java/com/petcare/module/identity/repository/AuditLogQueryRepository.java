package com.petcare.module.identity.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.petcare.module.identity.dto.AuditLogFilter;

/**
 * Đường đọc duy nhất của {@code audit_logs} (UC11, identity-v1 #36; convention 08 §8.2; docs/adr/0038 mục 2). Bảng do
 * {@code platform/audit} ghi ({@code AuditLogRepository} chỉ có {@code save}); module QT sở hữu màn hình xem. Lệch có
 * chủ đích khỏi convention 02 (JpaRepository): truy vấn động chỉ ghép điều kiện có thật ({@link AuditLogQuery}),
 * SELECT đúng 11 cột, không nạp entity. Không tự mở transaction: NamedParameterJdbcTemplate dùng connection mà
 * {@code JpaTransactionManager} đã gắn vào thread trong {@code AuditLogQueryService} (readOnly, REPEATABLE READ).
 */
@Repository
public class AuditLogQueryRepository {

    private static final RowMapper<AuditLogRow> ROW_MAPPER = AuditLogQueryRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(AuditLogFilter filter) {
        AuditLogQuery query = AuditLogQuery.of(filter);
        Long total = jdbc.queryForObject(query.countSql(), query.params(), Long.class);
        return total == null ? 0 : total;
    }

    public List<AuditLogRow> findPage(AuditLogFilter filter, long offset, int limit) {
        AuditLogQuery query = AuditLogQuery.of(filter);
        return jdbc.query(query.pageSql(), query.pageParams(offset, limit), ROW_MAPPER);
    }

    /** Cột số có thể NULL đọc bằng {@code getObject(…, Long.class)}: {@code getLong} biến NULL thành 0. */
    private static AuditLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditLogRow(
                rs.getLong("id"),
                rs.getObject("actor_account_id", Long.class),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", Long.class),
                rs.getString("before_data"),
                rs.getString("after_data"),
                rs.getString("reason"),
                rs.getString("ip_address"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
