package com.petcare.module.content.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.petcare.module.content.entity.PageKey;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code PageContent} (#1, #2, #14). */
public record PageContentResponse(
        @Schema(description = "Khóa trang", example = "ABOUT")
        PageKey key,
        @Schema(description = "Tiêu đề trang", example = "Về chúng tôi", nullable = true)
        String title,
        @Schema(description = "Nội dung JSON tự do theo từng trang (A3)", type = "object",
                example = "{\"sections\":[{\"heading\":\"Sứ mệnh\"}]}")
        JsonNode content,
        @Schema(description = "Lần sửa cuối", example = "2026-10-23T03:00:00Z")
        Instant updatedAt) {
}
