package com.petcare.module.ai.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code ImportAiDocumentResponse} (#14): các tài liệu {@code DRAFT} đã tạo từ một file (có thể nhiều phần). */
public record ImportAiDocumentResponse(
        @Schema(description = "Kiểu file đã đọc", example = "DOCX", requiredMode = Schema.RequiredMode.REQUIRED)
        String fileKind,
        @Schema(description = "Tài liệu nháp đã tạo, theo thứ tự phần; file dài hơn 20 000 ký tự tách theo mục `##`",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<AiDocumentResponse> documents) {
}
