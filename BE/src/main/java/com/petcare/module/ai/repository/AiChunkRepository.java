package com.petcare.module.ai.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiChunk;

/**
 * Bảng {@code ai_chunks} (docs/adr/0130). Ghi bằng native vì cột {@code embedding vector(768)} không ánh xạ vào entity;
 * vector truyền dạng chữ {@code [0.1,0.2,…]} rồi {@code CAST(… AS vector)}. Mọi câu ghi là {@code MANDATORY}: bước hoán
 * đổi của ST22 phải xóa đoạn cũ và ghi đoạn mới trong cùng một transaction (BR-AI-16).
 */
public interface AiChunkRepository extends JpaRepository<AiChunk, Long> {

    /** Số đoạn đang phục vụ của một phiên bản tài liệu ({@code chunkCount} của {@code AiDocument}). */
    long countByDocumentIdAndDocVersion(Long documentId, int docVersion);

    /**
     * Xóa <b>mọi</b> đoạn của tài liệu, không chỉ phiên bản cũ: nạp lại đúng phiên bản hiện tại phải thay được đoạn của
     * chính nó mà không vướng {@code UNIQUE (document_id, doc_version, ordinal)}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "DELETE FROM ai_chunks WHERE document_id = :documentId", nativeQuery = true)
    int deleteAllOf(@Param("documentId") Long documentId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO ai_chunks (document_id, doc_version, ordinal, heading, text, embedding, embedding_model,
                                   created_at, updated_at)
            VALUES (:documentId, :docVersion, :ordinal, :heading, :text, CAST(:embedding AS vector), :model, :now, :now)
            """, nativeQuery = true)
    int insert(@Param("documentId") Long documentId, @Param("docVersion") int docVersion,
            @Param("ordinal") int ordinal, @Param("heading") String heading, @Param("text") String text,
            @Param("embedding") String embedding, @Param("model") String model, @Param("now") Instant now);
}
