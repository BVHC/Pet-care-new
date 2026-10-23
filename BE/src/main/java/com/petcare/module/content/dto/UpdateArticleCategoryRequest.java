package com.petcare.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** content-v1 {@code UpdateArticleCategoryRequest} (#5). Không có {@code slug}: slug không đổi (A1 của contract). */
public record UpdateArticleCategoryRequest(
        @Schema(description = "Tên mới, duy nhất", example = "Dinh dưỡng")
        @Size(max = 100, message = "Tên chuyên mục tối đa 100 ký tự")
        @Pattern(regexp = ".*\\S.*", message = "Tên chuyên mục không được để trống")
        String name,
        @Schema(description = "Ẩn / hiện chuyên mục ở bộ lọc công khai", example = "true")
        @JsonProperty("isHidden") Boolean isHidden) {
}
