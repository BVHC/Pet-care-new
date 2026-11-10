package com.petcare.module.ai.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSource;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.entity.DocumentTopic;
import com.petcare.module.ai.entity.IndexStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ai-v1 {@code AiDocument} (#6–#11). {@code sourceNote} rỗng (nháp chưa có nguồn, V34 bắt NOT NULL) trả {@code null}.
 * Không có {@code content_hash}: mã băm phác đồ là chi tiết nội bộ của ST22.
 */
public record AiDocumentResponse(
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

        @Schema(description = "Markdown ≤ 20 000 ký tự", example = "## Mũi đầu tiên\nChó con thường tiêm mũi đầu từ 6 tuần tuổi.")
        String content,

        @Schema(description = "Lớp 1 — nguồn (BR-AI-14)", example = "WSAVA Vaccination Guidelines 2024, mục 3", nullable = true)
        String sourceNote,

        @Schema(description = "Lớp 2 — bác sĩ thú y rà soát", example = "BS. Nguyễn Văn Nam", nullable = true)
        String reviewerName,

        @Schema(description = "Ngày rà soát", example = "2026-11-05", nullable = true)
        LocalDate reviewedOn,

        @Schema(description = "Trạng thái (FSM #13)")
        DocumentStatus status,

        @Schema(description = "Phiên bản nội dung, tăng mỗi lần đổi nội dung (BR-AI-16)", example = "2")
        int version,

        @Schema(description = "Phiên bản đang có đoạn phục vụ; 0 = chưa nạp", example = "1")
        int indexedVersion,

        @Schema(description = "Trạng thái chỉ mục của ST22")
        IndexStatus indexStatus,

        @Schema(description = "Lý do nạp lỗi (tối đa 300 ký tự)", example = "[PROVIDER_RATE_LIMIT] Nhà cung cấp AI trả HTTP 429",
                nullable = true)
        String indexError,

        @Schema(description = "Số đoạn đang phục vụ (của phiên bản đã nạp)", example = "5")
        int chunkCount,

        @Schema(description = "Lúc xuất bản gần nhất", example = "2026-11-09T08:00:00Z", nullable = true)
        Instant publishedAt,

        @Schema(description = "Lần sửa cuối", example = "2026-11-10T09:30:00Z")
        Instant updatedAt) {

    public static AiDocumentResponse from(AiDocument doc, long chunkCount) {
        String sourceNote = doc.getSourceNote() == null || doc.getSourceNote().isEmpty() ? null : doc.getSourceNote();
        return new AiDocumentResponse(doc.getId(), doc.getSourceType(), doc.getTitle(), doc.getTopic(),
                doc.getSpecies(), doc.getContent(), sourceNote, doc.getReviewerName(), doc.getReviewedOn(),
                doc.getStatus(), doc.getVersion(), doc.getIndexedVersion(), doc.getIndexStatus(), doc.getIndexError(),
                (int) chunkCount, doc.getPublishedAt(), doc.getUpdatedAt());
    }
}
