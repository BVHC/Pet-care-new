package com.petcare.module.ai.service;

/**
 * Tài liệu cần nạp chỉ mục (ST22). Phát trong transaction của UC93 hoặc của đồng bộ phác đồ; chỉ được chuyển cho
 * {@code KnowledgeIndexer} sau khi transaction commit (docs/adr/0130), nên rollback không để lại lượt nạp mồ côi.
 */
public record AiIndexRequested(Long documentId) {
}
