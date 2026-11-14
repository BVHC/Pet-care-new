package com.petcare.module.ai.dto;

import static com.petcare.module.ai.entity.ChatSourceType.DATABASE;
import static com.petcare.module.ai.entity.ChatSourceType.GENERAL_KNOWLEDGE;
import static com.petcare.module.ai.entity.ChatSourceType.KNOWLEDGE_BASE;
import static com.petcare.module.ai.entity.ChatSourceType.KNOWLEDGE_BASE_AND_GENERAL;
import static com.petcare.module.ai.entity.ChatSourceType.SAFETY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.chat.ChatTexts;
import com.petcare.module.ai.entity.ChatResult;

/**
 * Nguồn của câu trả lời (BR-AI-34, docs/adr/0136 D4): {@code SAFETY} cho cấp cứu và từ chối y khoa, {@code DATABASE} cho
 * câu cá nhân và nghiệp vụ, {@code null} cho {@code OUT_OF_SCOPE} và {@code UNAVAILABLE}. "Không gắn KNOWLEDGE_BASE khi
 * không có đoạn nào được dùng".
 */
class ChatResponseSourceTypeTest {

    private static final List<ChatSource> ONE_SOURCE = List.of(new ChatSource(7L, "Tài liệu"));

    @Test
    void anAnswerWithSourcesIsKnowledgeBase() {
        assertThat(ChatResponse.answered("Chải lông mỗi ngày.", ONE_SOURCE, ChatTexts.DISCLAIMER).sourceType())
                .isEqualTo(KNOWLEDGE_BASE);
    }

    @Test
    void anAnswerWithTheGeneralPrefixAndNoSourceIsGeneralKnowledge() {
        assertThat(ChatResponse.answered(ChatTexts.GENERAL_PREFIX + " Cắt móng 3 tuần.", List.of(), ChatTexts.DISCLAIMER)
                .sourceType()).isEqualTo(GENERAL_KNOWLEDGE);
    }

    @Test
    void anAnswerWithSourcesAndTheGeneralPrefixIsBoth() {
        assertThat(ChatResponse.answered(ChatTexts.GENERAL_PREFIX + " Cắt móng 3 tuần.", ONE_SOURCE, ChatTexts.DISCLAIMER)
                .sourceType()).isEqualTo(KNOWLEDGE_BASE_AND_GENERAL);
    }

    @Test
    void emergencyAndMedicalRefusalAreSafety() {
        assertThat(ChatResponse.emergency(ChatTexts.EMERGENCY, List.of()).sourceType()).isEqualTo(SAFETY);
        assertThat(ChatResponse.canned(ChatResult.MEDICAL_REFUSAL, ChatTexts.MEDICAL_REFUSAL, true).sourceType())
                .isEqualTo(SAFETY);
    }

    @Test
    void personalAndBusinessAnswersAreDatabase() {
        assertThat(ChatResponse.personal("Bạn chưa có lịch hẹn sắp tới.", false).sourceType()).isEqualTo(DATABASE);
        assertThat(ChatResponse.needPetChoice(ChatTexts.NEED_PET_CHOICE, List.of()).sourceType()).isEqualTo(DATABASE);
        assertThat(ChatResponse.canned(ChatResult.NEED_LOGIN, ChatTexts.NEED_LOGIN, false).sourceType()).isEqualTo(DATABASE);
        ChatResponse business = ChatResponse.database("Giá dịch vụ tại Pet Care: ...");
        assertThat(business.result()).isEqualTo(ChatResult.ANSWERED);
        assertThat(business.sourceType()).isEqualTo(DATABASE);
        assertThat(business.sources()).isEmpty();
        assertThat(business.bookingHint()).isFalse();
    }

    @Test
    void outOfScopeAndUnavailableHaveNoSource() {
        assertThat(ChatResponse.canned(ChatResult.OUT_OF_SCOPE, ChatTexts.OUT_OF_SCOPE, true).sourceType()).isNull();
        assertThat(ChatResponse.canned(ChatResult.UNAVAILABLE, ChatTexts.UNAVAILABLE, true).sourceType()).isNull();
    }
}
