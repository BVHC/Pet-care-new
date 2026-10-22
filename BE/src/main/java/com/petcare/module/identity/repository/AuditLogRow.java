package com.petcare.module.identity.repository;

import java.time.Instant;

/**
 * Một dòng {@code audit_logs} đọc bởi {@link AuditLogQueryRepository} (identity-v1 #36; docs/adr/0038). Đúng 11 cột của
 * bảng; {@code beforeData} / {@code afterData} là text {@code jsonb} nguyên văn, {@code null} khi cột NULL.
 */
public record AuditLogRow(
        long id,
        Long actorAccountId,
        String actorEmail,
        String action,
        String entityType,
        Long entityId,
        String beforeData,
        String afterData,
        String reason,
        String ipAddress,
        Instant createdAt) {
}
