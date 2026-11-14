package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.fsm.AiDocumentTransitionHandler;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Một transaction cho một bài viết của {@link ArticleSync} (BR-AI-30, docs/adr/0134 "Đã cài ở T90"). Khóa dòng tài liệu
 * trước khi đọc (như {@link CatalogProtocolWriter}), nên người quản lý đang xuất bản hay lưu trữ cùng tài liệu được xếp hàng
 * và ST22 thấy trạng thái mới.
 * <ul>
 * <li>Bài chưa có tài liệu: tạo {@code DRAFT} (FSM #1), không người rà soát, không nạp chỉ mục. Không bao giờ tự
 * {@code PUBLISHED} lần đầu: duyệt bài (UC82) không thay lớp 2 của BR-AI-14.</li>
 * <li>{@code DRAFT}: nội dung bài đổi (mã băm khác {@code content_hash}) thì theo bài, {@code version + 1}. Mã băm giống
 * thì không đụng, kể cả khi người quản lý đã sửa tay nháp ({@code content_hash} không do đường sửa tay ghi).</li>
 * <li>{@code PUBLISHED}: nội dung bài đổi thì về {@code DRAFT} (FSM #8), xóa người và ngày rà soát, ghi audit actor hệ
 * thống; chatbot thôi phục vụ bản cũ ngay khi commit.</li>
 * <li>{@code ARCHIVED}: chỉ xuất bản lại (FSM #5, audit, xếp nạp) khi chính ST22 đã lưu trữ ({@code updated_by} null), đã
 * từng được rà soát và nội dung bài không đổi kể từ bản đó. Người quản lý lưu trữ thì không bao giờ tự quay lại (ADR-0130
 * phương án A). Bài đổi nội dung trong lúc tài liệu {@code ARCHIVED} thì tài liệu ở nguyên: không có cạnh
 * {@code ARCHIVED → DRAFT}, và nội dung chưa duyệt không được đi thẳng lên {@code PUBLISHED} (nợ ở ADR-0134).</li>
 * </ul>
 */
@Service
public class ArticleWriter {

    static final String PREFIX = "ARTICLE-";

    private final AiDocumentRepository docs;
    private final AiDocumentTransitionHandler fsm;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditRecorder auditRecorder;

    public ArticleWriter(AiDocumentRepository docs, AiDocumentTransitionHandler fsm, Clock clock,
            ApplicationEventPublisher events, AuditRecorder auditRecorder) {
        this.docs = docs;
        this.fsm = fsm;
        this.clock = clock;
        this.events = events;
        this.auditRecorder = auditRecorder;
    }

    /** {@code true} nếu đã tạo, đổi nội dung, hạ về nháp hoặc xuất bản lại; {@code false} nếu không có gì đổi. */
    @Transactional
    public boolean upsert(Long articleId, String title, String content, String sourceNote, String hash) {
        Long existing = docs.findIdByExternalId(PREFIX + articleId).orElse(null);
        if (existing == null) {
            AiDocument created = docs.saveAndFlush(AiDocument.articleDraft(PREFIX + articleId, title, content, hash, sourceNote));
            audit(AiAuditActions.DOCUMENT_CREATED, created, null);
            return true;
        }
        AiDocument doc = docs.findByIdForUpdate(existing).orElse(null);
        if (doc == null) {
            return false;
        }
        return switch (doc.getStatus()) {
            case DRAFT -> !hash.equals(doc.getContentHash()) && follow(doc, title, content, hash);
            case PUBLISHED -> !hash.equals(doc.getContentHash()) && demote(doc, title, content, hash);
            case ARCHIVED -> doc.getUpdatedBy() == null && doc.getReviewerName() != null && hash.equals(doc.getContentHash())
                    && republish(doc);
        };
    }

    /**
     * Bài không còn xuất bản: lưu trữ tài liệu {@code PUBLISHED} của nó (FSM #4, audit actor hệ thống, {@code updated_by}
     * null để về sau được phép tự quay lại). Khóa dòng rồi kiểm lại trạng thái: tài liệu vừa được người quản lý lưu trữ thì
     * không đụng. Truy hồi lọc {@code status} trong SQL nên có hiệu lực ngay khi commit (BR-AI-16).
     */
    @Transactional
    public boolean archiveRemoved(Long documentId) {
        AiDocument doc = docs.findByIdForUpdate(documentId).orElse(null);
        if (doc == null || doc.getStatus() != DocumentStatus.PUBLISHED) {
            return false;
        }
        fsm.validateTransition(DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED);   // #4
        Map<String, Object> before = KnowledgeService.snapshot(doc);
        doc.archive(null);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_ARCHIVED, doc, before);
        return true;
    }

    /** Nháp theo bài: chưa phục vụ chatbot nên không cần chuyển trạng thái; audit để còn dấu vết bản bị thay. */
    private boolean follow(AiDocument doc, String title, String content, String hash) {
        Map<String, Object> before = KnowledgeService.snapshot(doc);
        doc.applySourceContent(title, content, hash);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_UPDATED, doc, before);
        return true;
    }

    private boolean demote(AiDocument doc, String title, String content, String hash) {
        fsm.validateTransition(DocumentStatus.PUBLISHED, DocumentStatus.DRAFT);   // #8
        Map<String, Object> before = KnowledgeService.snapshot(doc);
        doc.demoteForReview(title, content, hash);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_UPDATED, doc, before);
        return true;
    }

    private boolean republish(AiDocument doc) {
        fsm.validateTransition(DocumentStatus.ARCHIVED, DocumentStatus.PUBLISHED);   // #5
        Map<String, Object> before = KnowledgeService.snapshot(doc);
        doc.publish(Instant.now(clock), null);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_PUBLISHED, doc, before);
        events.publishEvent(new AiIndexRequested(doc.getId()));
        return true;
    }

    private void audit(String action, AiDocument doc, Map<String, Object> before) {
        AuditEntry entry = AuditEntry.of(action).entity(AiAuditActions.ENTITY_TYPE, doc.getId()).actor(null, null)
                .after(KnowledgeService.snapshot(doc));
        auditRecorder.record(before == null ? entry : entry.before(before));
    }
}
