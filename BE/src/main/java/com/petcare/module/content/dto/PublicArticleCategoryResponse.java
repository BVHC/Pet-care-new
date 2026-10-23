package com.petcare.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code PublicArticleCategory} (#15): chuyên mục không ẩn. */
public record PublicArticleCategoryResponse(
        @Schema(description = "Mã chuyên mục", example = "3")
        Long categoryId,
        @Schema(description = "Tên chuyên mục", example = "Chăm sóc thú cưng")
        String name,
        @Schema(description = "Đường dẫn", example = "cham-soc-thu-cung")
        String slug) {
}
