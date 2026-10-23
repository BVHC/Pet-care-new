package com.petcare.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * content-v1 {@code UpdateArticleRequest} (#10). Trường vắng = giữ nguyên; trường gửi lên không được để trống. Muốn xóa
 * ảnh bìa / tóm tắt thì gửi chuỗi rỗng.
 */
public record UpdateArticleRequest(
        @Schema(description = "Chuyển sang chuyên mục khác", example = "4")
        @Positive(message = "Mã chuyên mục phải là số dương")
        Long categoryId,
        @Schema(description = "Tiêu đề", example = "5 điều cần biết khi tiêm phòng cho chó")
        @Size(max = 250, message = "Tiêu đề tối đa 250 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tiêu đề không được để trống")
        String title,
        @Schema(description = "Đường dẫn; không đổi được sau lần xuất bản đầu", example = "tiem-phong-cho-cho")
        @Size(max = 270, message = "Đường dẫn tối đa 270 ký tự")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang")
        String slug,
        @Schema(description = "Ảnh bìa (https); chuỗi rỗng để xóa", example = "https://cdn.petcare.vn/anh/bia.jpg")
        @Size(max = 500, message = "Địa chỉ ảnh bìa tối đa 500 ký tự")
        @Pattern(regexp = "^(https://\\S+)?$", message = "Ảnh bìa phải là địa chỉ https")
        String coverImageUrl,
        @Schema(description = "Tóm tắt; chuỗi rỗng để xóa", example = "Lịch tiêm cơ bản cho chó con.")
        @Size(max = 500, message = "Tóm tắt tối đa 500 ký tự")
        String summary,
        @Schema(description = "Nội dung HTML (được lọc khi lưu)", example = "<p>Chó con cần tiêm mũi đầu lúc 6 tuần.</p>")
        @Pattern(regexp = "(?s).*\\S.*", message = "Nội dung không được để trống")
        String content,
        @Schema(description = "Tác giả hiển thị", example = "BS. Nguyễn An")
        @Size(max = 100, message = "Tên tác giả tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên tác giả không được để trống")
        String authorDisplayName) {
}
