package com.petcare.module.care.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Số thông báo chưa đọc của người đang đăng nhập (care-v1 schema {@code UnreadCount}, #6). */
public record UnreadCountResponse(
        @Schema(description = "Số thông báo chưa đọc", example = "3")
        int count) {
}
