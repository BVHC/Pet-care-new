package com.petcare.module.identity.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Schema {@code AuditLogEntry} của identity-v1 (#36, UC11; BR-QT-15; docs/adr/0038 mục 7). Đúng thứ đã ghi lúc sự kiện
 * xảy ra, không join: {@code actorEmail} là email lúc ghi, {@code actorAccountId} có thể là tài khoản đã bị xóa (cột
 * không có FK). {@code beforeData} / {@code afterData} là text {@code jsonb} chèn nguyên văn ({@link JsonRawValue}):
 * luôn là JSON object hợp lệ ({@code AuditRecorder} chỉ nhận snapshot là object), không parse lại nên không mất độ chính
 * xác số; thứ tự khóa theo cách {@code jsonb} lưu.
 */
public record AuditLogEntryResponse(
        @Schema(description = "Mã bản ghi audit", example = "1024")
        Long id,

        @Schema(description = "Tài khoản thực hiện; null với tác vụ hệ thống. Giữ nguyên cả khi tài khoản đã bị xóa",
                example = "7")
        Long actorAccountId,

        @Schema(description = "Email người thực hiện tại thời điểm ghi", example = "admin@petcare.vn")
        String actorEmail,

        @Schema(description = "Mã hành động", example = "STAFF_CREATED")
        String action,

        @Schema(description = "Bảng của đối tượng bị tác động", example = "accounts")
        String entityType,

        @Schema(description = "Id của đối tượng; null khi đối tượng không có id số", example = "42")
        Long entityId,

        @Schema(description = "Ảnh chụp trước khi đổi (JSON object nguyên văn); null nếu không có",
                implementation = Object.class, types = {"object", "null"}, example = "{\"status\": \"ACTIVE\"}")
        @JsonRawValue String beforeData,

        @Schema(description = "Ảnh chụp sau khi đổi (JSON object nguyên văn); null nếu không có",
                implementation = Object.class, types = {"object", "null"}, example = "{\"status\": \"DISABLED\"}")
        @JsonRawValue String afterData,

        @Schema(description = "Lý do người thực hiện nhập (nếu có)", example = "Nghỉ việc")
        String reason,

        @Schema(description = "IP client", example = "203.113.10.25")
        String ipAddress,

        @Schema(description = "Thời điểm ghi", example = "2026-10-14T02:30:00Z")
        Instant createdAt) {
}
