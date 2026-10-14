package com.petcare.module.identity.repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.petcare.module.identity.dto.AuditLogFilter;

/**
 * Câu SQL của {@code GET /api/audit-logs} (identity-v1 #36; docs/adr/0038 mục 2, 6). Chỉ ghép điều kiện của tham số có
 * giá trị — <b>không</b> dùng {@code :x IS NULL OR …}: khi PgJDBC chuyển sang server-prepared statement, generic plan
 * của Postgres không dùng được index cho điều kiện đó. Index dùng theo tổ hợp: {@code ix_audit_logs_actor_created_at}
 * (người thực hiện), {@code ix_audit_logs_entity} (đối tượng), {@code ix_audit_logs_created_at} (khoảng thời gian, không
 * lọc, hoặc {@code action} trong khoảng ≤ 31 ngày). {@code AuditLogViewIT} EXPLAIN đúng chuỗi do lớp này sinh.
 */
public final class AuditLogQuery {

    static final String COLUMNS = "id, actor_account_id, actor_email, action, entity_type, entity_id, before_data, "
            + "after_data, reason, ip_address, created_at";
    static final String ORDER = " ORDER BY created_at DESC, id DESC";

    private final String where;
    private final MapSqlParameterSource params;

    private AuditLogQuery(String where, MapSqlParameterSource params) {
        this.where = where;
        this.params = params;
    }

    public static AuditLogQuery of(AuditLogFilter filter) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filter.actorAccountId() != null) {
            conditions.add("actor_account_id = :actorAccountId");
            params.addValue("actorAccountId", filter.actorAccountId(), Types.BIGINT);
        }
        if (filter.action() != null) {
            conditions.add("action = :action");
            params.addValue("action", filter.action(), Types.VARCHAR);
        }
        if (filter.entityType() != null) {
            conditions.add("entity_type = :entityType");
            params.addValue("entityType", filter.entityType(), Types.VARCHAR);
        }
        if (filter.entityId() != null) {
            conditions.add("entity_id = :entityId");
            params.addValue("entityId", filter.entityId(), Types.BIGINT);
        }
        if (filter.from() != null) {
            conditions.add("created_at >= :from");
            params.addValue("from", utc(filter.from()), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        if (filter.to() != null) {
            conditions.add("created_at <= :to");
            params.addValue("to", utc(filter.to()), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return new AuditLogQuery(where, params);
    }

    public String countSql() {
        return "SELECT count(*) FROM audit_logs" + where;
    }

    public String pageSql() {
        return "SELECT " + COLUMNS + " FROM audit_logs" + where + ORDER + " LIMIT :limit OFFSET :offset";
    }

    /** Tham số của {@link #countSql()}. */
    public MapSqlParameterSource params() {
        return copy();
    }

    /** Tham số của {@link #pageSql()}: bộ lọc + {@code limit}, {@code offset}. */
    public MapSqlParameterSource pageParams(long offset, int limit) {
        return copy().addValue("limit", limit, Types.INTEGER).addValue("offset", offset, Types.BIGINT);
    }

    private MapSqlParameterSource copy() {
        MapSqlParameterSource copy = new MapSqlParameterSource();
        for (String name : params.getParameterNames()) {
            copy.addValue(name, params.getValue(name), params.getSqlType(name));
        }
        return copy;
    }

    /** PgJDBC không nhận {@link Instant} qua {@code setObject}; {@link OffsetDateTime} ở UTC thì có. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
