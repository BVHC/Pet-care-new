package com.petcare.module.ai.fsm;

import static com.petcare.module.ai.entity.DocumentStatus.ARCHIVED;
import static com.petcare.module.ai.entity.DocumentStatus.DRAFT;
import static com.petcare.module.ai.entity.DocumentStatus.PUBLISHED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * FSM #13 "Tài liệu tri thức" (docs/07-ai-subsystem.md §9). #1 — → {@code DRAFT}; #2 {@code DRAFT → PUBLISHED}; #3 (sửa)
 * và #7 (ST22 đổi phác đồ) {@code PUBLISHED → PUBLISHED}; #4 {@code PUBLISHED → ARCHIVED} (người quản lý, hoặc ST22 khi
 * loại vaccine hết phác đồ); #5 {@code ARCHIVED → PUBLISHED} (người quản lý với {@code MANUAL}; ST22 với phác đồ do chính nó
 * lưu trữ); #6 {@code DRAFT → ARCHIVED}. Không có cạnh {@code ARCHIVED → ARCHIVED}, nên sửa tài liệu đã lưu trữ bị
 * 409. ST22 tạo thẳng tài liệu {@code CATALOG_PROTOCOL} ở {@code PUBLISHED} (07 §9, dòng dưới bảng): không phải một
 * dòng đánh số của bảng nên không nằm ở {@code initialStates} (docs/adr/0130).
 */
@Component
public class AiDocumentTransitionHandler extends StateMachineBase<DocumentStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "tài liệu tri thức";

    @Override
    public Map<DocumentStatus, Set<DocumentStatus>> allowedTransitions() {
        return Map.of(
                DRAFT, Set.of(PUBLISHED, ARCHIVED),         // #2 | #6
                PUBLISHED, Set.of(PUBLISHED, ARCHIVED),     // #3, #7 | #4
                ARCHIVED, Set.of(PUBLISHED));               // #5
    }

    @Override
    public Set<DocumentStatus> initialStates() {
        return Set.of(DRAFT);                               // #1
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
