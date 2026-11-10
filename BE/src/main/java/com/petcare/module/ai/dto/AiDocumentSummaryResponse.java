package com.petcare.module.ai.dto;

import java.time.Instant;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSource;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.entity.DocumentTopic;
import com.petcare.module.ai.entity.IndexStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code AiDocumentSummary} (#5): một dòng của danh sách, không có nội dung. */
public record AiDocumentSummaryResponse(
        @Schema(description = "Mã tài liệu", example = "4")
        Long documentId,

        @Schema(description = "Nguồn: soạn tay hoặc phác đồ sinh từ danh mục")
        DocumentSource sourceType,

        @Schema(description = "Tiêu đề", example = "Lịch tiêm cho chó con")
        String title,

        @Schema(description = "Chủ đề")
        DocumentTopic topic,

        @Schema(description = "Loài áp dụng")
        DocumentSpecies species,

        @Schema(description = "Trạng thái (FSM #13)")
        DocumentStatus status,

        @Schema(description = "Phiên bản nội dung, tăng mỗi lần đổi nội dung (BR-AI-16)", example = "2")
        int version,

        @Schema(description = "Trạng thái chỉ mục của ST22")
        IndexStatus indexStatus,

        @Schema(description = "Lần sửa cuối", example = "2026-11-10T09:30:00Z")
        Instant updatedAt) {

    public static AiDocumentSummaryResponse from(AiDocument doc) {
        return new AiDocumentSummaryResponse(doc.getId(), doc.getSourceType(), doc.getTitle(), doc.getTopic(),
                doc.getSpecies(), doc.getStatus(), doc.getVersion(), doc.getIndexStatus(), doc.getUpdatedAt());
    }
}
