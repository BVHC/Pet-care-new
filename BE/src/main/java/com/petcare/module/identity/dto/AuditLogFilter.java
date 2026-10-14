package com.petcare.module.identity.dto;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Bộ lọc của {@code GET /api/audit-logs} (identity-v1 #36, UC11; docs/adr/0038 mục 3). So khớp chính xác; khoảng thời
 * gian đóng {@code [from, to]}. Ba luật chéo tham số (R1–R3) kiểm bằng {@link ValidAuditLogFilter} sau
 * {@code @PreAuthorize}; không phải rule {@code BR-…} nên trả {@code VALIDATION_FAILED}.
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

    /**
     * R3: khoảng tối đa khi lọc theo {@code action} mà không có mốc chọn lọc. Hằng kỹ thuật (không có index trên
     * {@code action} — nợ D021), không phải [CFG].
     */
    public static final Duration MAX_ACTION_WINDOW = Duration.ofDays(31);

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

    /** Có mốc dùng được index: người thực hiện, hoặc đúng một đối tượng (bảng + id). */
    public boolean hasSelectiveAnchor() {
        return actorAccountId != null || (entityType != null && entityId != null);
    }

    /** Có đủ {@code from}, {@code to} và cách nhau không quá {@link #MAX_ACTION_WINDOW}. */
    public boolean hasBoundedWindow() {
        return from != null && to != null && Duration.between(from, to).compareTo(MAX_ACTION_WINDOW) <= 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
