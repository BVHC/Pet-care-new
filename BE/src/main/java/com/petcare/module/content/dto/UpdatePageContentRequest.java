package com.petcare.module.content.dto;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** content-v1 {@code UpdatePageContentRequest} (#2). {@code content} phải là JSON object ≤ 64 KB (A3 của T32). */
public record UpdatePageContentRequest(
        @Schema(description = "Tiêu đề trang", example = "Về chúng tôi", nullable = true)
        @Size(max = 250, message = "Tiêu đề tối đa 250 ký tự")
        String title,
        @Schema(description = "Nội dung JSON object", type = "object",
                example = "{\"sections\":[{\"heading\":\"Sứ mệnh\"}]}", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập nội dung trang")
        JsonNode content) {
}
