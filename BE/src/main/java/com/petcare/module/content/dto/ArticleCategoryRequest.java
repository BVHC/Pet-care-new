package com.petcare.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** content-v1 {@code ArticleCategoryRequest} (#4). Slug: chữ thường, số, gạch ngang đơn (A2 của T32). */
public record ArticleCategoryRequest(
        @Schema(description = "Tên chuyên mục, duy nhất", example = "Chăm sóc thú cưng",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập tên chuyên mục")
        @Size(max = 100, message = "Tên chuyên mục tối đa 100 ký tự")
        String name,
        @Schema(description = "Đường dẫn, duy nhất, không đổi sau khi tạo", example = "cham-soc-thu-cung",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập đường dẫn")
        @Size(max = 120, message = "Đường dẫn tối đa 120 ký tự")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang")
        String slug) {
}
