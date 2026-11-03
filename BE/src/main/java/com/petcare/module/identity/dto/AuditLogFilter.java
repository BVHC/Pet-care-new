package com.petcare.module.identity.dto;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Bộ lọc của {@code GET /api/audit-logs} (identity-v1 #36, UC11; docs/adr/0038 mục 3). So khớp chính xác; khoảng thời
 * gian đóng {@code [from, to]}. Hai luật chéo tham số (R1, R2) kiểm bằng {@link ValidAuditLogFilter} sau
 * {@code @PreAuthorize}; không phải rule {@code BR-…} nên trả {@code VALIDATION_FAILED}. R3 (giới hạn 31 ngày khi lọc
 * {@code action}) đã bỏ cùng index {@code ix_audit_logs_action_created_at} (V31, ADR-0098, trả nợ D021).
 *
 * <p>{@code action} / {@code entityType} rỗng hoặc toàn khoảng trắng → {@code null} (coi như không gửi): Spring truyền
 * {@code ""} cho tham số {@code String} khi URL có {@code ?action=}, còn {@code Long} / {@code OffsetDateTime} rỗng đã
 * tự thành {@code null}. Giá trị khác giữ nguyên văn.
 */
@ValidAuditLogFilter
public record AuditLogFilter(
        Long actorAccountId,
        String action,
        String entityType,
        Long entityId,
        Instant from,
        Instant to) {

    public AuditLogFilter {
        action = blankToNull(action);
        entityType = blankToNull(entityType);
    }

    /** Dựng từ tham số HTTP: {@code from} / {@code to} mang offset (contract {@code datetime}, 00-method L81). */
    public static AuditLogFilter of(Long actorAccountId, String action, String entityType, Long entityId,
            OffsetDateTime from, OffsetDateTime to) {
        return new AuditLogFilter(actorAccountId, action, entityType, entityId,
                from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
