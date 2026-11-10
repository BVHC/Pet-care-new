package com.petcare.module.ai.entity;

/** {@code ai_documents.status} (docs/07 §9, FSM #13). Cạnh hợp lệ nằm ở {@code AiDocumentTransitionHandler}. */
public enum DocumentStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
