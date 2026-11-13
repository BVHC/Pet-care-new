package com.petcare.module.ai.dto;

import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentTopic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ai-v1 {@code ImportAiDocumentRequest} (#14, BR-AI-29, docs/adr/0134 D1). File gửi dạng base64 trong JSON (không
 * multipart) để cùng cơ chế hợp đồng với các endpoint khác; kiểu file, cỡ và nội dung do {@code DocumentFileReader} kiểm.
 */
public record ImportAiDocumentRequest(
        @NotBlank(message = "Tên file không được để trống")
        @Size(max = 255, message = "Tên file tối đa 255 ký tự")
        @Schema(description = "Tên file kèm phần mở rộng (.md, .txt, .docx, .pdf)", example = "cham-soc-cho-con.docx",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String fileName,
        @NotBlank(message = "Nội dung file không được để trống")
        @Schema(description = "Nội dung file mã hóa base64 (không kèm tiền tố data:), tối đa 2 MB trước khi mã hóa",
                example = "IyBDaMSDbSBzw7NjIGNow7MgY29u", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentBase64,
        @NotNull(message = "Chủ đề không được để trống")
        @Schema(description = "Chủ đề áp cho mọi tài liệu tạo ra", requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentTopic topic,
        @NotNull(message = "Loài áp dụng không được để trống")
        @Schema(description = "Loài áp dụng; ALL = mọi loài", requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentSpecies species,
        @Size(max = 500, message = "Ghi chú nguồn tối đa 500 ký tự")
        @Schema(description = "Lớp 1 — nguồn; bỏ trống thì ghi \"Nạp từ file <tên> (<kiểu>, <kB>)\"", example = "WSAVA 2024",
                nullable = true)
        String sourceNote) {
}
