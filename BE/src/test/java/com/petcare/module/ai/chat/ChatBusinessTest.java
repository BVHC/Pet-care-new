package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.ai.dto.ChatRequest;
import com.petcare.module.ai.dto.ChatResponse;
import com.petcare.module.ai.entity.AiChatLog;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.entity.ChatSourceType;
import com.petcare.module.ai.repository.AiChatLogRepository;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Đường BUSINESS trong {@code ChatService} (BR-AI-31, docs/adr/0134 "Đã cài ở T90") và {@code sourceType} ở nhật ký
 * (BR-AI-34). Kiểm thứ tự lớp bằng thứ lớp giả nhận được: cấp cứu, bệnh án và ý định cá nhân thắng business; business
 * thắng hai lớp T92 và truy hồi; công tắc tắt thì {@link BusinessAnswerService} không bị gọi.
 */
class ChatBusinessTest {

    private final FakeEmbeddingClient embeddings = new FakeEmbeddingClient();
    private final FakeLlmClient llm = new FakeLlmClient();
    private final ChunkRetriever retriever = mock(ChunkRetriever.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final BusinessAnswerService business = mock(BusinessAnswerService.class);
    private final AiChatLogRepository logs = mock(AiChatLogRepository.class);
    private ChatService service;

    @BeforeEach
    void setUp() {
        when(config.getInt(ConfigKey.AI_CHAT_MAX_QUESTION_CHARS)).thenReturn(500);
        when(config.getInt(ConfigKey.AI_CHAT_MAX_PREVIOUS_QUESTIONS)).thenReturn(3);
        when(config.getInt(ConfigKey.AI_RETRIEVAL_TOP_K)).thenReturn(5);
        when(config.getDecimal(ConfigKey.AI_RETRIEVAL_MIN_SIMILARITY)).thenReturn(new BigDecimal("0.60"));
        when(config.getBool(ConfigKey.AI_CHAT_BUSINESS_INTENT_ENABLED)).thenReturn(true);
        BranchQueryApi branches = mock(BranchQueryApi.class);
        when(branches.findEmergencyBranches()).thenReturn(List.of());
        when(retriever.nearest(any(float[].class), anyInt())).thenReturn(List.of());
        service = new ChatService(config, new EmergencyDetector(), new PersonalIntentRouter(), new DoseGuard(), branches,
                embeddings, llm, retriever, logs, new SimpleMeterRegistry(),
                mock(com.petcare.module.customer.api.CustomerQueryApi.class),
                mock(com.petcare.module.customer.api.PetQueryApi.class), mock(PersonalService.class), business);
    }

    private ChatResponse ask(String question) {
        return service.ask(new ChatRequest(question, null));
    }

    private AiChatLog loggedRow() {
        ArgumentCaptor<AiChatLog> row = ArgumentCaptor.forClass(AiChatLog.class);
        verify(logs).save(row.capture());
        return row.getValue();
    }

    @Test
    void aBusinessAnswerIsReturnedFromTheDatabaseWithoutEmbeddingOrModelEvenWhenGeneralKnowledgeIsOn() {
        when(config.getBool(ConfigKey.AI_CHAT_GENERAL_KNOWLEDGE_ENABLED)).thenReturn(true);
        when(business.answer(any())).thenReturn(Optional.of(ChatTexts.BUSINESS_NO_PRICE));

        ChatResponse response = ask("Giá massage bao nhiêu?");

        assertThat(response.result()).isEqualTo(ChatResult.ANSWERED);
        assertThat(response.answer()).isEqualTo(ChatTexts.BUSINESS_NO_PRICE);
        assertThat(response.sourceType()).isEqualTo(ChatSourceType.DATABASE);
        assertThat(response.sources()).isEmpty();
        assertThat(embeddings.calls()).as("không nhúng").isZero();
        assertThat(llm.calls()).as("không gọi mô hình").isEmpty();
        AiChatLog row = loggedRow();
        assertThat(row.getResult()).isEqualTo(ChatResult.ANSWERED);
        assertThat(row.getSourceType()).isEqualTo(ChatSourceType.DATABASE);
        assertThat(row.getQuestion()).isEqualTo("Giá massage bao nhiêu?");
    }

    @Test
    void theBusinessLayerOnlySeesTheMaskedQuestion() {
        when(business.answer(any())).thenReturn(Optional.empty());

        ask("Giá tắm bao nhiêu? Gọi 0912345678");

        verify(business).answer(argThat(question -> !question.contains("0912345678")));
    }

    @Test
    void whenTheBusinessLayerHasNoIntentTheQuestionGoesOnToRetrieval() {
        when(business.answer(any())).thenReturn(Optional.empty());

        ChatResponse response = ask("Chải lông bao lâu một lần");

        assertThat(response.result()).isEqualTo(ChatResult.OUT_OF_SCOPE);
        assertThat(embeddings.calls()).isEqualTo(1);
    }

    @Test
    void withTheSwitchOffTheBusinessLayerIsNeverConsultedAndAPriceQuestionStaysOutOfScope() {
        when(config.getBool(ConfigKey.AI_CHAT_BUSINESS_INTENT_ENABLED)).thenReturn(false);

        ChatResponse response = ask("Giá tiêm vaccine dại bao nhiêu?");

        assertThat(response.result()).isEqualTo(ChatResult.OUT_OF_SCOPE);
        assertThat(response.sourceType()).isNull();
        verifyNoInteractions(business);
    }

    @Test
    void anEmergencyBeatsABusinessQuestion() {
        ChatResponse response = ask("Chó bị co giật, chi nhánh nào gần nhất?");

        assertThat(response.result()).isEqualTo(ChatResult.EMERGENCY);
        assertThat(response.sourceType()).isEqualTo(ChatSourceType.SAFETY);
        verifyNoInteractions(business);
    }

    @Test
    void aMedicalRecordQuestionBeatsABusinessQuestion() {
        ChatResponse response = ask("Bệnh án của nhà tôi ở chi nhánh nào?");

        assertThat(response.result()).isEqualTo(ChatResult.MEDICAL_REFUSAL);
        verifyNoInteractions(business);
    }

    @Test
    void aPersonalIntentBeatsABusinessQuestion() {
        ChatResponse response = ask("Lịch hẹn sắp tới của tôi ở chi nhánh nào?");

        assertThat(response.result()).isEqualTo(ChatResult.NEED_LOGIN);
        verifyNoInteractions(business);
    }

    @Test
    void theBusinessLayerRunsBeforeTheMedicalRiskKeywordLayerOfT92() {
        when(config.getBool(ConfigKey.AI_CHAT_GENERAL_KNOWLEDGE_ENABLED)).thenReturn(true);
        when(business.answer(any())).thenReturn(Optional.of("Giá dịch vụ tại Pet Care: ..."));

        ChatResponse response = ask("Giá khám bệnh gì bao nhiêu?");

        assertThat(response.result()).as("không bị chặn thành MEDICAL_REFUSAL").isEqualTo(ChatResult.ANSWERED);
        assertThat(response.sourceType()).isEqualTo(ChatSourceType.DATABASE);
    }

    @Test
    void theRetrievalAnswerIsLoggedAsKnowledgeBaseAndTheGeneralOneAsGeneralKnowledge() {
        when(business.answer(any())).thenReturn(Optional.empty());
        when(retriever.nearest(any(float[].class), anyInt())).thenReturn(
                List.of(new RetrievedChunk(100, "Tài liệu 0", "ALL", null, "Đoạn 0", 0.82)));
        llm.reply(true, "Chải lông mỗi ngày.");

        ChatResponse response = ask("Chải lông cho chó thế nào");

        assertThat(response.sourceType()).isEqualTo(ChatSourceType.KNOWLEDGE_BASE);
        assertThat(loggedRow().getSourceType()).isEqualTo(ChatSourceType.KNOWLEDGE_BASE);
    }
}
