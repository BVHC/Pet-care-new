package com.petcare.module.ai.listener;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.petcare.module.ai.service.AiIndexRequested;
import com.petcare.module.ai.service.KnowledgeIndexer;

/**
 * Kích hoạt ST22 sau commit (07 §3). Khác quy ước "không AFTER_COMMIT" của hệ quả liên-aggregate (CLAUDE.md): ST22 là
 * tác vụ hệ thống chậm (gọi mạng), không phải hệ quả nghiệp vụ phải cùng transaction; độ bền nằm ở dòng
 * {@code PENDING} trong CSDL và lượt quét khi khởi động / định kỳ (docs/adr/0130). Chỉ xếp hàng cho luồng nền, không
 * làm việc trong luồng gọi.
 */
@Component
public class KnowledgeIndexTrigger {

    private final KnowledgeIndexer indexer;

    public KnowledgeIndexTrigger(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndexRequested(AiIndexRequested event) {
        indexer.requestIndex(event.documentId());
    }
}
