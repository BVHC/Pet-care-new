package com.petcare.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code ArticleCategory} (#3–5). */
public record ArticleCategoryResponse(
        @Schema(description = "Mã chuyên mục", example = "3")
        Long categoryId,
        @Schema(description = "Tên chuyên mục", example = "Chăm sóc thú cưng")
        String name,
        @Schema(description = "Đường dẫn, không đổi sau khi tạo", example = "cham-soc-thu-cung")
        String slug,
        @Schema(description = "Đang ẩn khỏi bộ lọc công khai (BR-BV-04)", example = "false")
        @JsonProperty("isHidden") boolean isHidden,
        @Schema(description = "Số bài viết thuộc chuyên mục (mọi trạng thái)", example = "12")
        int articleCount) {
}
