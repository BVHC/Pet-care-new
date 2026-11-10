package com.petcare.module.ai.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code ai_documents} (07 §8, ROOT, FSM #13): tài liệu tri thức {@code MANUAL} hoặc {@code CATALOG_PROTOCOL}.
 * {@code version} là bộ đếm nghiệp vụ tăng mỗi lần đổi nội dung (BR-AI-16), <b>không</b> phải {@code @Version} của JPA:
 * Hibernate sẽ tăng nó ở mọi UPDATE, kể cả khi ST22 ghi {@code index_status}. Các cột chỉ mục ({@code indexed_version},
 * {@code index_status}, {@code index_error}) do ST22 ghi bằng câu UPDATE có điều kiện ở {@code AiDocumentRepository},
 * không qua entity. Các method ghi <b>không</b> tự kiểm trạng thái: {@code KnowledgeService} đã khóa dòng và qua
 * {@code AiDocumentTransitionHandler}.
 */
@Getter
@Entity
@Table(name = "ai_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDocument extends TimestampedEntity {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int CONTENT_MAX_LENGTH = 20_000;
    public static final int SOURCE_NOTE_MAX_LENGTH = 500;
    public static final int REVIEWER_NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 30)
    private DocumentSource sourceType;

    @Column(name = "vaccine_type_id", updatable = false)
    private Long vaccineTypeId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", nullable = false, length = 30)
    private DocumentTopic topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false, length = 30)
    private DocumentSpecies species;

    @Column(name = "content", nullable = false)
    private String content;

    /** Lớp 1 — nguồn. V34 bắt NOT NULL với {@code MANUAL}, nên nháp chưa có nguồn lưu chuỗi rỗng (docs/adr/0130). */
    @Column(name = "source_note", length = SOURCE_NOTE_MAX_LENGTH)
    private String sourceNote;

    @Column(name = "reviewer_name", length = REVIEWER_NAME_MAX_LENGTH)
    private String reviewerName;

    @Column(name = "reviewed_on")
    private LocalDate reviewedOn;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DocumentStatus status;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "indexed_version", nullable = false)
    private int indexedVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 30)
    private IndexStatus indexStatus = IndexStatus.PENDING;

    @Column(name = "index_error", length = 300)
    private String indexError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** Tài liệu soạn tay, luôn bắt đầu ở {@code DRAFT} (Tài liệu#1). */
    public static AiDocument manualDraft(String title, DocumentTopic topic, DocumentSpecies species, String content,
            String sourceNote, String reviewerName, LocalDate reviewedOn, Long updatedBy) {
        AiDocument doc = new AiDocument();
        doc.sourceType = DocumentSource.MANUAL;
        doc.status = DocumentStatus.DRAFT;
        doc.edit(title, topic, species, content, sourceNote, reviewerName, reviewedOn, updatedBy);
        return doc;
    }

    /** Phác đồ tiêm sinh từ danh mục: ST22 tạo thẳng ở {@code PUBLISHED}, không có người rà soát (BR-AI-14). */
    public static AiDocument catalogProtocol(Long vaccineTypeId, String title, DocumentSpecies species, String content,
            String contentHash, Instant now) {
        AiDocument doc = new AiDocument();
        doc.sourceType = DocumentSource.CATALOG_PROTOCOL;
        doc.vaccineTypeId = vaccineTypeId;
        doc.status = DocumentStatus.PUBLISHED;
        doc.publishedAt = now;
        doc.topic = DocumentTopic.VACCINATION;
        doc.title = title;
        doc.species = species;
        doc.content = content;
        doc.contentHash = contentHash;
        return doc;
    }

    public void edit(String title, DocumentTopic topic, DocumentSpecies species, String content, String sourceNote,
            String reviewerName, LocalDate reviewedOn, Long updatedBy) {
        this.title = title;
        this.topic = topic;
        this.species = species;
        this.content = content;
        this.sourceNote = sourceNote;
        this.reviewerName = reviewerName;
        this.reviewedOn = reviewedOn;
        this.updatedBy = updatedBy;
    }

    /** Tài liệu#2 và #5: chờ ST22 nạp; {@link #version} không đổi (đoạn của đúng phiên này vẫn dùng được). */
    public void publish(Instant now, Long updatedBy) {
        this.status = DocumentStatus.PUBLISHED;
        this.publishedAt = now;
        this.updatedBy = updatedBy;
        requestIndex();
    }

    /** Tài liệu#4 và #6. */
    public void archive(Long updatedBy) {
        this.status = DocumentStatus.ARCHIVED;
        this.updatedBy = updatedBy;
    }

    /** Tài liệu#3 và #7: đổi nội dung hoặc tiêu đề (cả hai nằm trong văn bản đem nhúng, BR-AI-15). */
    public void bumpVersion() {
        this.version++;
        requestIndex();
    }

    /** Đánh dấu cần nạp lại (nút "Nạp lại", xuất bản, đổi nội dung); đoạn cũ vẫn phục vụ tới lúc ST22 hoán đổi. */
    public void requestIndex() {
        this.indexStatus = IndexStatus.PENDING;
        this.indexError = null;
    }

    /** Tài liệu#7: ST22 dựng lại nội dung từ phác đồ đang hoạt động. */
    public void applyCatalogContent(String title, String content, String contentHash) {
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
        this.version++;
        requestIndex();
    }
}
