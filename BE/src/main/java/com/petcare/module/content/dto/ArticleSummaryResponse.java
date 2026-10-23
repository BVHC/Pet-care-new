package com.petcare.module.content.dto;

import java.time.Instant;

import com.petcare.module.content.entity.ArticleStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code ArticleSummary} (#7): một dòng của danh sách bài viết. */
public record ArticleSummaryResponse(
        @Schema(description = "Mã bài viết", example = "21")
        Long articleId,
        @Schema(description = "Mã chuyên mục", example = "3")
        Long categoryId,
        @Schema(description = "Tiêu đề", example = "5 điều cần biết khi tiêm phòng cho chó")
        String title,
        @Schema(description = "Đường dẫn", example = "tiem-phong-cho-cho")
        String slug,
        @Schema(description = "Trạng thái", example = "PUBLISHED")
        ArticleStatus status,
        @Schema(description = "Lần xuất bản đầu", example = "2026-10-23T03:00:00Z", nullable = true)
        Instant firstPublishedAt,
        @Schema(description = "Sửa lúc", example = "2026-10-23T03:00:00Z")
        Instant updatedAt) {
}
