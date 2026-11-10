package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.fsm.AiDocumentTransitionHandler;
import com.petcare.module.ai.repository.AiDocumentRepository;

/**
 * Một transaction cho một loại vaccine của {@link CatalogProtocolSync} (docs/adr/0130): tạo tài liệu phác đồ ở
 * {@code PUBLISHED} hoặc dựng lại nội dung (FSM #13 dòng #7). Khóa dòng trước khi đọc, nên một tài liệu vừa bị người
 * quản lý lưu trữ không bị ghi đè về {@code PUBLISHED}. Không ghi audit: đây là việc của hệ thống (actor null), không
 * phải thao tác của người dùng (BR-AI-17 nêu tạo / sửa / xuất bản / lưu trữ / nạp lại do người quản lý).
 */
@Service
public class CatalogProtocolWriter {

    private final AiDocumentRepository docs;
    private final AiDocumentTransitionHandler fsm;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public CatalogProtocolWriter(AiDocumentRepository docs, AiDocumentTransitionHandler fsm, Clock clock,
            ApplicationEventPublisher events) {
        this.docs = docs;
        this.fsm = fsm;
        this.clock = clock;
        this.events = events;
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
        if (doc == null || doc.getStatus() != DocumentStatus.PUBLISHED || hash.equals(doc.getContentHash())) {
            return false;   // đã lưu trữ (người quản lý không muốn chatbot dùng) hoặc phác đồ không đổi
        }
        fsm.validateTransition(DocumentStatus.PUBLISHED, DocumentStatus.PUBLISHED);   // #7
        doc.applyCatalogContent(title, content, hash);
        docs.saveAndFlush(doc);
        events.publishEvent(new AiIndexRequested(doc.getId()));
        return true;
    }
}
