package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.chat.CategoryMapper.Verdict;
import com.petcare.module.ai.entity.ChatIntent;
import com.petcare.module.ai.llm.AnswerCategory;

/**
 * Bảng ánh xạ {@code category} của chế độ mở thành quyết định và ý định nhật ký (docs/adr/0136 D2, "Đã cài ở T92 (phần 2)").
 * Chỉ nhóm {@code PET_CARE} và {@code OUT_OF_DOMAIN} được trả lời; mọi thứ còn lại, kể cả giá trị thiếu hoặc lạ, là từ chối
 * và KHÔNG BAO GIỜ là {@link Verdict#KNOWLEDGE}, {@link Verdict#GENERAL} hay {@link Verdict#OPEN}.
 */
class CategoryMapperTest {

    @Test
    void petCareWithADocumentAboveTheThresholdIsAnsweredFromTheKnowledgeBase() {
        assertThat(CategoryMapper.decide(AnswerCategory.PET_CARE, true)).isEqualTo(new CategoryMapper.Decision(Verdict.KNOWLEDGE, ChatIntent.PET_CARE));
    }

    @Test
    void petCareWithoutADocumentIsAnsweredFromGeneralKnowledge() {
        assertThat(CategoryMapper.decide(AnswerCategory.PET_CARE, false)).isEqualTo(new CategoryMapper.Decision(Verdict.GENERAL, ChatIntent.PET_CARE));
    }

    @Test
    void outOfDomainIsOpenAnswerWhetherOrNotADocumentWasRetrieved() {
        assertThat(CategoryMapper.decide(AnswerCategory.OUT_OF_DOMAIN, true)).isEqualTo(new CategoryMapper.Decision(Verdict.OPEN, ChatIntent.OUT_OF_DOMAIN));
        assertThat(CategoryMapper.decide(AnswerCategory.OUT_OF_DOMAIN, false)).isEqualTo(new CategoryMapper.Decision(Verdict.OPEN, ChatIntent.OUT_OF_DOMAIN));
    }

    @Test
    void medicalRiskIsAlwaysAMedicalRefusal() {
        assertThat(CategoryMapper.decide(AnswerCategory.MEDICAL_RISK, true)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_MEDICAL, ChatIntent.MEDICAL_RISK));
        assertThat(CategoryMapper.decide(AnswerCategory.MEDICAL_RISK, false)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_MEDICAL, ChatIntent.MEDICAL_RISK));
    }

    @Test
    void harmfulOrInjectedContentIsRefusedAsOutOfScope() {
        assertThat(CategoryMapper.decide(AnswerCategory.BLOCKED, true)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.OUT_OF_DOMAIN));
        assertThat(CategoryMapper.decide(AnswerCategory.BLOCKED, false)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.OUT_OF_DOMAIN));
    }

    @Test
    void aBusinessQuestionThatSlippedPastTheKeywordLayerIsRefusedAsOutOfScope() {
        assertThat(CategoryMapper.decide(AnswerCategory.BUSINESS, true)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN));
        assertThat(CategoryMapper.decide(AnswerCategory.BUSINESS, false)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN));
    }

    @Test
    void aMissingOrUnknownCategoryFailsSafeToOutOfScope() {
        assertThat(CategoryMapper.decide(null, true)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN));
        assertThat(CategoryMapper.decide(null, false)).isEqualTo(new CategoryMapper.Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN));
    }
}
