package com.petcare.module.identity.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response của {@code POST /api/accounts/{id}/lock} (identity-v1 {@code LockResult}, docs/adr/0035). {@code account} đọc
 * sau khi khóa ({@code isLocked = true}); {@code pendingWork} là các quy trình dở dang để ADMIN xem ngay sau khi khóa
 * (BR-QT-11), không bao giờ {@code null} — rỗng là {@code []}.
 */
public record LockResult(
        @Schema(description = "Tài khoản sau khi khóa")
        AccountSummary account,

        @Schema(description = "Quy trình dở dang của người bị khóa, sắp theo loại rồi entityId")
        List<PendingWorkItem> pendingWork) {
}
