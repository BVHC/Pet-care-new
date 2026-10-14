package com.petcare.module.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.petcare.module.identity.dto.AuditLogFilter;

/**
 * SQL của {@code GET /api/audit-logs} (docs/adr/0038 mục 2, 6): chỉ điều kiện có thật, không {@code IS NULL OR}, đúng 11
 * cột, sắp {@code created_at DESC, id DESC}; câu đếm cùng {@code WHERE}. Index dùng thật được {@code AuditLogViewIT}
 * chứng minh bằng EXPLAIN trên đúng các chuỗi này.
 */
class AuditLogQueryTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void noFilterHasNoWhere() {
        AuditLogQuery query = AuditLogQuery.of(new AuditLogFilter(null, null, null, null, null, null));

        assertThat(query.countSql()).isEqualTo("SELECT count(*) FROM audit_logs");
        assertThat(query.pageSql()).isEqualTo("SELECT id, actor_account_id, actor_email, action, entity_type, "
                + "entity_id, before_data, after_data, reason, ip_address, created_at FROM audit_logs "
                + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        assertThat(query.params().getParameterNames()).isEmpty();
    }

    @Test
    void everyFilterBecomesOneConditionInFixedOrder() {
        AuditLogQuery query = AuditLogQuery.of(new AuditLogFilter(5L, "LOGIN_FAILED", "accounts", 9L, FROM, TO));

        String where = " WHERE actor_account_id = :actorAccountId AND action = :action AND entity_type = :entityType"
                + " AND entity_id = :entityId AND created_at >= :from AND created_at <= :to";
        assertThat(query.countSql()).isEqualTo("SELECT count(*) FROM audit_logs" + where);
        assertThat(query.pageSql()).contains(where + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");

        MapSqlParameterSource params = query.params();
        assertThat(params.getValues()).containsOnlyKeys("actorAccountId", "action", "entityType", "entityId", "from",
                "to");
        assertThat(params.getValue("actorAccountId")).isEqualTo(5L);
        assertThat(params.getValue("action")).isEqualTo("LOGIN_FAILED");
        assertThat(params.getValue("entityType")).isEqualTo("accounts");
        assertThat(params.getValue("entityId")).isEqualTo(9L);
        assertThat(params.getValue("from")).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(params.getSqlType("from")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(params.getValue("to")).isEqualTo(OffsetDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void eachShapeOnlyHasItsOwnConditions() {
        assertThat(AuditLogQuery.of(new AuditLogFilter(5L, null, null, null, null, null)).countSql())
                .endsWith(" WHERE actor_account_id = :actorAccountId");
        assertThat(AuditLogQuery.of(new AuditLogFilter(null, null, "accounts", 9L, null, null)).countSql())
                .endsWith(" WHERE entity_type = :entityType AND entity_id = :entityId");
        assertThat(AuditLogQuery.of(new AuditLogFilter(null, "X_Y", null, null, FROM, TO)).countSql())
                .endsWith(" WHERE action = :action AND created_at >= :from AND created_at <= :to");
        assertThat(AuditLogQuery.of(new AuditLogFilter(null, null, null, null, null, TO)).countSql())
                .endsWith(" WHERE created_at <= :to");
    }

    @Test
    void noOptionalPredicateTrick() {
        AuditLogQuery query = AuditLogQuery.of(new AuditLogFilter(5L, "X_Y", "accounts", 9L, FROM, TO));

        assertThat(query.pageSql().toUpperCase()).doesNotContain("IS NULL", " OR ", "COALESCE");
        assertThat(query.countSql().toUpperCase()).doesNotContain("IS NULL", " OR ", "COALESCE");
    }

    @Test
    void pageParamsAddLimitAndOffsetWithoutTouchingCountParams() {
        AuditLogQuery query = AuditLogQuery.of(new AuditLogFilter(5L, null, null, null, null, null));

        MapSqlParameterSource page = query.pageParams(40L, 20);

        assertThat(page.getValues()).containsOnlyKeys("actorAccountId", "limit", "offset");
        assertThat(page.getValue("limit")).isEqualTo(20);
        assertThat(page.getValue("offset")).isEqualTo(40L);
        assertThat(query.params().getValues()).containsOnlyKeys("actorAccountId");
    }

    @Test
    void valuesAreBoundNeverConcatenated() {
        AuditLogQuery query = AuditLogQuery.of(
                new AuditLogFilter(null, "X'; DROP TABLE audit_logs; --", "a' OR '1'='1", 9L, null, null));

        assertThat(query.pageSql()).doesNotContain("DROP", "'1'='1");
        assertThat(query.params().getValue("action")).isEqualTo("X'; DROP TABLE audit_logs; --");
    }
}
