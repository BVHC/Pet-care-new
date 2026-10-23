package com.petcare.module.content.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code PublicArticle} (#17): bài đã xuất bản, nội dung HTML đã lọc. */
public record PublicArticleResponse(
        @Schema(description = "Mã bài viết", example = "21")
        Long articleId,
        @Schema(description = "Tiêu đề", example = "5 điều cần biết khi tiêm phòng cho chó")
        String title,
        @Schema(description = "Đường dẫn", example = "tiem-phong-cho-cho")
        String slug,
        @Schema(description = "Tên chuyên mục", example = "Chăm sóc thú cưng")
        String categoryName,
        @Schema(description = "Đường dẫn chuyên mục", example = "cham-soc-thu-cung")
        String categorySlug,
        @Schema(description = "Ảnh bìa", example = "https://cdn.petcare.vn/anh/bia.jpg", nullable = true)
        String coverImageUrl,
        @Schema(description = "Tóm tắt", example = "Lịch tiêm cơ bản cho chó con.", nullable = true)
        String summary,
        @Schema(description = "Nội dung HTML đã lọc", example = "<p>Chó con cần tiêm mũi đầu lúc 6 tuần tuổi.</p>")
        String content,
        @Schema(description = "Tác giả hiển thị", example = "BS. Nguyễn An")
        String authorDisplayName,
        @Schema(description = "Xuất bản lúc (lần đầu)", example = "2026-10-23T03:00:00Z")
        Instant publishedAt) {
}
