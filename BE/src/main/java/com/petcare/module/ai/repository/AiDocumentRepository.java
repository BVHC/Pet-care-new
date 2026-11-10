package com.petcare.module.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiDocument;

import jakarta.persistence.LockModeType;

/**
 * Bảng {@code ai_documents} (docs/adr/0130). Trạng thái là <b>literal</b> trong SQL để predicate khớp index; các câu ghi
 * của ST22 điều kiện theo {@code version} và {@code status}, nên một tài liệu vừa được sửa hay lưu trữ không bị ghi đè
 * kết quả nạp của phiên bản cũ. Ghi {@code index_status} không đổi {@code updated_at}: "mới sửa" là việc của người
 * quản lý, không phải của ST22.
 */
public interface AiDocumentRepository extends JpaRepository<AiDocument, Long>, JpaSpecificationExecutor<AiDocument> {

    /**
     * Khóa dòng ({@code FOR NO KEY UPDATE}) cho UC93 và cho bước hoán đổi của ST22: hai bên nối tiếp nhau trên cùng một
     * dòng, nên ST22 thấy phiên bản vừa được sửa và bỏ lượt nạp cũ. {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM AiDocument d WHERE d.id = :id")
    Optional<AiDocument> findByIdForUpdate(@Param("id") Long id);

    /** Mã tài liệu phác đồ của một loại vaccine, không nạp entity: bên gọi khóa dòng rồi mới đọc (CLAUDE.md, thứ tự khóa). */
    @Query("SELECT d.id FROM AiDocument d WHERE d.vaccineTypeId = :vaccineTypeId")
    Optional<Long> findIdByVaccineTypeId(@Param("vaccineTypeId") Long vaccineTypeId);

    /** Tài liệu {@code PUBLISHED} chưa có đoạn khớp phiên bản hiện tại (ST22, 07 §5.3 bước 1), theo id tăng. */
    @Query(value = """
            SELECT id FROM ai_documents
            WHERE status = 'PUBLISHED' AND (index_status <> 'INDEXED' OR indexed_version < version)
            ORDER BY id""", nativeQuery = true)
    List<Long> findIdsNeedingIndex();

    /** Kết thúc nạp: chỉ ghi khi dòng vẫn là đúng phiên bản vừa nạp và vẫn {@code PUBLISHED}; trả 1 nếu ghi được. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE ai_documents SET indexed_version = :version, index_status = 'INDEXED', index_error = NULL
            WHERE id = :id AND version = :version AND status = 'PUBLISHED'""", nativeQuery = true)
    int markIndexed(@Param("id") Long id, @Param("version") int version);

    /** Nạp lỗi: đoạn cũ vẫn phục vụ (không đụng {@code indexed_version} và {@code ai_chunks}); cùng điều kiện như trên. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE ai_documents SET index_status = 'FAILED', index_error = :error
            WHERE id = :id AND version = :version AND status = 'PUBLISHED'""", nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("version") int version, @Param("error") String error);
}
