package com.petcare.module.content.dto;

import java.time.Instant;

import com.petcare.module.content.entity.ArticleStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code Article} (#8–12): chi tiết bài cho SUPER_MANAGER. {@code content} là HTML đã lọc. */
public record ArticleResponse(
        @Schema(description = "Mã bài viết", example = "21")
        Long articleId,
        @Schema(description = "Mã chuyên mục", example = "3")
        Long categoryId,
        @Schema(description = "Tiêu đề", example = "5 điều cần biết khi tiêm phòng cho chó")
        String title,
        @Schema(description = "Đường dẫn, duy nhất; khóa sau lần xuất bản đầu (BR-BV-01)", example = "tiem-phong-cho-cho")
        String slug,
        @Schema(description = "Ảnh bìa (https)", example = "https://cdn.petcare.vn/anh/bia.jpg", nullable = true)
        String coverImageUrl,
        @Schema(description = "Tóm tắt", example = "Lịch tiêm cơ bản cho chó con.", nullable = true)
        String summary,
        @Schema(description = "Nội dung HTML đã lọc", example = "<p>Chó con cần tiêm mũi đầu lúc 6 tuần tuổi.</p>")
        String content,
        @Schema(description = "Tác giả hiển thị", example = "BS. Nguyễn An")
        String authorDisplayName,
        @Schema(description = "Trạng thái (BR-BV-02)", example = "DRAFT")
        ArticleStatus status,
        @Schema(description = "Lần xuất bản đầu; khác null thì không xóa, không đổi slug",
                example = "2026-10-23T03:00:00Z", nullable = true)
        Instant firstPublishedAt,
        @Schema(description = "Tạo lúc", example = "2026-10-23T03:00:00Z")
        Instant createdAt,
        @Schema(description = "Sửa lúc", example = "2026-10-23T03:00:00Z")
        Instant updatedAt) {
}
