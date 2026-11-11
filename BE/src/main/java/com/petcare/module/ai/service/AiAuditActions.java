package com.petcare.module.ai.service;

/** Mã hành động audit của phân hệ AI (BR-AI-17, mở rộng BR-QT-15); {@code entity_type} là {@code ai_documents}. */
final class AiAuditActions {

    static final String ENTITY_TYPE = "ai_documents";
    static final String DOCUMENT_CREATED = "AI_DOCUMENT_CREATED";
    static final String DOCUMENT_UPDATED = "AI_DOCUMENT_UPDATED";
    static final String DOCUMENT_PUBLISHED = "AI_DOCUMENT_PUBLISHED";
    static final String DOCUMENT_ARCHIVED = "AI_DOCUMENT_ARCHIVED";
    static final String DOCUMENT_REINDEX_REQUESTED = "AI_DOCUMENT_REINDEX_REQUESTED";

    /** Hồ sơ gợi ý (UC94, BR-AI-17): bảng khác nên {@code entity_type} khác. */
    static final String PROFILE_ENTITY_TYPE = "ai_item_profiles";
    static final String ITEM_PROFILE_SAVED = "AI_ITEM_PROFILE_SAVED";
    static final String ITEM_PROFILE_CLEARED = "AI_ITEM_PROFILE_CLEARED";

    private AiAuditActions() {
    }
}
