package com.petcare.module.ai.entity;

import org.hibernate.annotations.Immutable;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code ai_chunks} (07 §8, PART của {@code ai_documents}): đoạn đã chia kèm vector. Entity <b>không ánh xạ cột
 * {@code embedding}</b> ({@code vector(768)}): Hibernate không có kiểu này nếu không thêm {@code hibernate-vector}, và
 * với {@code ddl-auto=validate} một ánh xạ {@code float[]} hay {@code String} sẽ làm khởi động lỗi. Đoạn được ghi bằng
 * câu native {@code CAST(? AS vector)} ở {@code AiChunkRepository} và đọc bằng câu truy hồi §5.1; entity chỉ để đếm và
 * liệt kê (docs/adr/0130).
 */
@Getter
@Immutable
@Entity
@Table(name = "ai_chunks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChunk extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "doc_version", nullable = false)
    private int docVersion;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "heading", length = 200)
    private String heading;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "embedding_model", nullable = false, length = 60)
    private String embeddingModel;
}
