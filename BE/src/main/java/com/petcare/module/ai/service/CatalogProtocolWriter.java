package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.fsm.AiDocumentTransitionHandler;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Một transaction cho một loại vaccine của {@link CatalogProtocolSync} (docs/adr/0130): tạo tài liệu phác đồ ở
 * {@code PUBLISHED}, dựng lại nội dung (FSM #13 dòng #7), lưu trữ khi hết phác đồ (dòng #4) hoặc xuất bản lại khi phác đồ
 * có lại (dòng #5, chỉ với tài liệu do chính ST22 lưu trữ, {@code updated_by} null). Khóa dòng trước khi đọc, nên một
 * tài liệu vừa bị người quản lý lưu trữ không bị ghi đè về {@code PUBLISHED}, và không bao giờ bị xuất bản lại. Tạo và
 * dựng lại không ghi audit (việc của hệ thống, không phải thao tác người dùng); lưu trữ và xuất bản lại có audit actor
 * hệ thống, vì làm chatbot ngừng hoặc dùng lại một phác đồ (BR-AI-17).
 */
@Service
public class CatalogProtocolWriter {

    private final AiDocumentRepository docs;
    private final AiDocumentTransitionHandler fsm;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditRecorder auditRecorder;

    public CatalogProtocolWriter(AiDocumentRepository docs, AiDocumentTransitionHandler fsm, Clock clock,
            ApplicationEventPublisher events, AuditRecorder auditRecorder) {
        this.docs = docs;
        this.fsm = fsm;
        this.clock = clock;
        this.events = events;
        this.auditRecorder = auditRecorder;
    }

    /** {@code true} nếu đã tạo mới hoặc đổi nội dung (và xếp hàng nạp sau commit); {@code false} nếu không có gì đổi. */
    @Transactional
    public boolean upsert(Long vaccineTypeId, DocumentSpecies species, String title, String content) {
        String hash = Sha256.hex(content);
        Long existing = docs.findIdByVaccineTypeId(vaccineTypeId).orElse(null);
        if (existing == null) {
            AiDocument created = docs.saveAndFlush(AiDocument.catalogProtocol(vaccineTypeId, title, species, content, hash,
                    Instant.now(clock)));
            events.publishEvent(new AiIndexRequested(created.getId()));
            return true;
        }
        AiDocument doc = docs.findByIdForUpdate(existing).orElse(null);
        if (doc == null) {
            return false;
        }
        if (doc.getStatus() == DocumentStatus.ARCHIVED) {
            // updated_by null = chính ST22 đã lưu trữ (archiveRetired); khác null = người quản lý không muốn chatbot dùng
            return doc.getUpdatedBy() == null && republish(doc, title, content, hash);
        }
        if (doc.getStatus() != DocumentStatus.PUBLISHED || hash.equals(doc.getContentHash())) {
            return false;   // chưa xuất bản (không xảy ra với phác đồ) hoặc phác đồ không đổi
        }
        fsm.validateTransition(DocumentStatus.PUBLISHED, DocumentStatus.PUBLISHED);   // #7
        doc.applyCatalogContent(title, content, hash);
        docs.saveAndFlush(doc);
        events.publishEvent(new AiIndexRequested(doc.getId()));
        return true;
    }

    /**
     * Lưu trữ tài liệu phác đồ của một loại vaccine đã hết phác đồ đang dùng (FSM #13 dòng #4, ADR-0130 #5). Khóa dòng rồi
     * kiểm lại trạng thái: tài liệu vừa được người quản lý lưu trữ thì không đụng và không audit. Ghi audit BR-AI-17 cùng
     * transaction, actor hệ thống ({@code updated_by} để null). Bỏ khỏi truy hồi nhờ lọc {@code status} trong SQL (BR-AI-16),
     * đoạn cũ không bị xóa. Bên gọi đã lọc {@code CATALOG_PROTOCOL}; trả {@code true} nếu đã lưu trữ.
     */
    @Transactional
    public boolean archiveRetired(Long documentId) {
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

    /**
     * Phác đồ của loại vaccine đã có lại sau khi ST22 tự lưu trữ (ADR-0130 #5, phương án A): xuất bản lại (FSM #5), dựng
     * lại nội dung nếu mã băm khác (như #7, {@code version + 1}), xếp nạp chỉ mục, audit {@code AI_DOCUMENT_PUBLISHED}
     * actor hệ thống. Chạy dưới khóa dòng của {@link #upsert}; không có tài liệu thứ hai vì chỉ mục duy nhất theo loại vaccine.
     */
    private boolean republish(AiDocument doc, String title, String content, String hash) {
        fsm.validateTransition(DocumentStatus.ARCHIVED, DocumentStatus.PUBLISHED);   // #5
        Map<String, Object> before = KnowledgeService.snapshot(doc);
        if (!hash.equals(doc.getContentHash())) {
            doc.applyCatalogContent(title, content, hash);
        }
        doc.publish(Instant.now(clock), null);
        docs.saveAndFlush(doc);
        audit(AiAuditActions.DOCUMENT_PUBLISHED, doc, before);
        events.publishEvent(new AiIndexRequested(doc.getId()));
        return true;
    }

    private void audit(String action, AiDocument doc, Map<String, Object> before) {
        auditRecorder.record(AuditEntry.of(action).entity(AiAuditActions.ENTITY_TYPE, doc.getId()).actor(null, null)
                .before(before).after(KnowledgeService.snapshot(doc)));
    }
}
