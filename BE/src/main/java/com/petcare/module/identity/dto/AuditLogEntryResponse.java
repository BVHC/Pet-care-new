package com.petcare.module.identity.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * Schema {@code AuditLogEntry} của identity-v1 (#36, UC11; BR-QT-15; docs/adr/0038 mục 7). Đúng thứ đã ghi lúc sự kiện
 * xảy ra, không join: {@code actorEmail} là email lúc ghi, {@code actorAccountId} có thể là tài khoản đã bị xóa (cột
 * không có FK). {@code beforeData} / {@code afterData} là text {@code jsonb} chèn nguyên văn ({@link JsonRawValue}):
 * luôn là JSON object hợp lệ ({@code AuditRecorder} chỉ nhận snapshot là object), không parse lại nên không mất độ chính
 * xác số; thứ tự khóa theo cách {@code jsonb} lưu.
 */
public record AuditLogEntryResponse(
        Long id,
        Long actorAccountId,
        String actorEmail,
        String action,
        String entityType,
        Long entityId,
        @JsonRawValue String beforeData,
        @JsonRawValue String afterData,
        String reason,
        String ipAddress,
        Instant createdAt) {
}
