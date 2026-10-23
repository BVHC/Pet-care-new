package com.petcare.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** content-v1 {@code CreateArticleRequest} (#8). Slug: chữ thường, số, gạch ngang đơn (A2 của T32). */
public record CreateArticleRequest(
        @Schema(description = "Chuyên mục", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn chuyên mục")
        @Positive(message = "Mã chuyên mục phải là số dương")
        Long categoryId,
        @Schema(description = "Tiêu đề", example = "5 điều cần biết khi tiêm phòng cho chó",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập tiêu đề")
        @Size(max = 250, message = "Tiêu đề tối đa 250 ký tự")
        String title,
        @Schema(description = "Đường dẫn, duy nhất toàn hệ thống (BR-BV-01)", example = "tiem-phong-cho-cho",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập đường dẫn")
        @Size(max = 270, message = "Đường dẫn tối đa 270 ký tự")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang")
        String slug,
        @Schema(description = "Ảnh bìa (https)", example = "https://cdn.petcare.vn/anh/bia.jpg", nullable = true)
        @Size(max = 500, message = "Địa chỉ ảnh bìa tối đa 500 ký tự")
        @Pattern(regexp = "^https://\\S+$", message = "Ảnh bìa phải là địa chỉ https")
        String coverImageUrl,
        @Schema(description = "Tóm tắt", example = "Lịch tiêm cơ bản cho chó con.", nullable = true)
        @Size(max = 500, message = "Tóm tắt tối đa 500 ký tự")
        String summary,
        @Schema(description = "Nội dung HTML (được lọc khi lưu)", example = "<p>Chó con cần tiêm mũi đầu lúc 6 tuần.</p>",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập nội dung")
        String content,
        @Schema(description = "Tác giả hiển thị", example = "BS. Nguyễn An", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập tên tác giả hiển thị")
        @Size(max = 100, message = "Tên tác giả tối đa 100 ký tự")
        String authorDisplayName) {
}
