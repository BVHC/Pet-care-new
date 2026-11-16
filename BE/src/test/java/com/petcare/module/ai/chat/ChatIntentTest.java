package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.petcare.module.ai.entity.AiChatLog;
import com.petcare.module.ai.entity.ChatIntent;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.llm.AnswerCategory;
import com.petcare.module.ai.repository.AiChatLogRepository;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.ai.entity.ChatResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@code ai_chat_logs.intent} (docs/adr/0136 D4, V57): mỗi lượt ghi đúng một ý định ở mọi nhánh của {@code ChatService}. Cột
 * không chứa nội dung câu hỏi (BR-AI-24), chỉ là nhãn cho thống kê.
 */
class ChatIntentTest {

    private static final long ACCOUNT = 91L;
    private static final long CUSTOMER = 7L;

    private final FakeEmbeddingClient embeddings = new FakeEmbeddingClient();
    private final FakeLlmClient llm = new FakeLlmClient();
    private final ChunkRetriever retriever = mock(ChunkRetriever.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final AiChatLogRepository logs = mock(AiChatLogRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final PersonalService personal = mock(PersonalService.class);
    private final BusinessAnswerService business = mock(BusinessAnswerService.class);
    private ChatService service;

    @BeforeEach
    void setUp() {
        when(config.getInt(ConfigKey.AI_CHAT_MAX_QUESTION_CHARS)).thenReturn(500);
        when(config.getInt(ConfigKey.AI_CHAT_MAX_PREVIOUS_QUESTIONS)).thenReturn(3);
        when(config.getInt(ConfigKey.AI_RETRIEVAL_TOP_K)).thenReturn(5);
        when(config.getDecimal(ConfigKey.AI_RETRIEVAL_MIN_SIMILARITY)).thenReturn(new BigDecimal("0.60"));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(pets.findLivingPetsOfCustomer(CUSTOMER)).thenReturn(List.of());
        when(personal.answer(any(), any(), anyList(), anyList()))
                .thenReturn(new PersonalService.Reply(ChatResult.PERSONAL, "Lịch", false, List.of()));
        BranchQueryApi branches = mock(BranchQueryApi.class);
        when(branches.findEmergencyBranches()).thenReturn(List.of());
        service = new ChatService(config, new EmergencyDetector(), new PersonalIntentRouter(), new DoseGuard(), branches,
                embeddings, llm, retriever, logs, new SimpleMeterRegistry(), customers, pets, personal, business,
                mock(PetContextBuilder.class));
        when(business.answer(any())).thenReturn(Optional.empty());
        similarities(0.82);
    }

    private void similarities(Double... values) {
        when(retriever.nearest(any(float[].class), anyInt())).thenReturn(java.util.stream.IntStream.range(0, values.length)
                .mapToObj(i -> new RetrievedChunk(100 + i, "Tài liệu " + i, "ALL", null, "Đoạn " + i, values[i])).toList());
    }

    private ChatIntent intentOf(String question) {
        service.ask(new ChatRequest(question, null));
        return loggedIntent();
    }

    private ChatIntent intentOfCustomer(String question) {
        service.askAsCustomer(ACCOUNT, new ChatRequest(question, null));
        return loggedIntent();
    }

    private ChatIntent loggedIntent() {
        ArgumentCaptor<AiChatLog> row = ArgumentCaptor.forClass(AiChatLog.class);
        verify(logs).save(row.capture());
        return row.getValue().getIntent();
    }

    @Test
    void emergencyIsLoggedAsEmergency() {
        assertThat(intentOf("Chó bị co giật liên tục")).isEqualTo(ChatIntent.EMERGENCY);
    }

    @Test
    void aMedicalRecordQuestionIsLoggedAsMedicalRecord() {
        assertThat(intentOf("Đơn thuốc của tôi hôm qua là gì?")).isEqualTo(ChatIntent.MEDICAL_RECORD);
    }

    @Test
    void personalIntentsAreLoggedByTheirName() {
        assertThat(intentOfCustomer("Sắp tới tôi có lịch hẹn không?")).isEqualTo(ChatIntent.APPOINTMENT_UPCOMING);
    }

    @Test
    void aGuestAskingForTheirScheduleIsStillLoggedWithTheDetectedIntent() {
        assertThat(intentOf("Sắp tới tôi có lịch hẹn không?")).isEqualTo(ChatIntent.APPOINTMENT_UPCOMING);
    }

    @Test
    void aPriceQuestionIsLoggedAsBusinessPrice() {
        when(config.getBool(ConfigKey.AI_CHAT_BUSINESS_INTENT_ENABLED)).thenReturn(true);
        when(business.answer(any())).thenReturn(Optional.of("Giá dịch vụ tại Pet Care: ..."));

        assertThat(intentOf("Giá tắm cho chó bao nhiêu")).isEqualTo(ChatIntent.BUSINESS_PRICE);
    }

    @Test
    void aBranchQuestionIsLoggedAsBusinessBranch() {
        when(config.getBool(ConfigKey.AI_CHAT_BUSINESS_INTENT_ENABLED)).thenReturn(true);
        when(business.answer(any())).thenReturn(Optional.of("Các chi nhánh Pet Care đang hoạt động: ..."));

        assertThat(intentOf("Pet Care có những chi nhánh nào")).isEqualTo(ChatIntent.BUSINESS_BRANCH);
    }

    @Test
    void aMedicalKeywordRefusalIsLoggedAsMedicalRisk() {
        when(config.getBool(ConfigKey.AI_CHAT_GENERAL_KNOWLEDGE_ENABLED)).thenReturn(true);

        assertThat(intentOf("Chó nôn vàng uống thuốc gì")).isEqualTo(ChatIntent.MEDICAL_RISK);
    }

    @Test
    void aGreetingHasNoIntentToReport() {
        when(config.getBool(ConfigKey.AI_CHAT_GENERAL_KNOWLEDGE_ENABLED)).thenReturn(true);

        assertThat(intentOf("hello")).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    void anAnsweredQuestionInTheClosedModeIsLoggedAsPetCare() {
        llm.reply(true, "Cho ăn hai bữa.");

        assertThat(intentOf("Chó nên ăn mấy bữa một ngày")).isEqualTo(ChatIntent.PET_CARE);
    }

    @Test
    void anOutOfScopeReplyWithoutACategoryIsLoggedAsUnknown() {
        llm.reply(false, "bỏ");

        assertThat(intentOf("Blockchain là gì")).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    void belowTheThresholdWithTheSwitchOffIsLoggedAsUnknown() {
        similarities(0.50);

        assertThat(intentOf("Bao lâu nên cắt móng")).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    void aDoseViolationIsLoggedAsMedicalRisk() {
        llm.reply(true, "Cho uống 1 viên mỗi ngày.");

        assertThat(intentOf("Chó bị ho nên làm gì")).isEqualTo(ChatIntent.MEDICAL_RISK);
    }

    @Test
    void aProviderFailureIsLoggedAsUnknown() {
        llm.failWith(new AiProviderException(ChatErrorKind.TIMEOUT, "x"));

        assertThat(intentOf("Chó nên ăn mấy bữa một ngày")).isEqualTo(ChatIntent.UNKNOWN);
    }

    @Test
    void theOpenModeLogsTheCategoryAsTheIntent() {
        when(config.getBool(ConfigKey.AI_CHAT_GENERAL_KNOWLEDGE_ENABLED)).thenReturn(true);
        when(config.getBool(ConfigKey.AI_CHAT_OPEN_DOMAIN_ENABLED)).thenReturn(true);
        llm.reply(AnswerCategory.OUT_OF_DOMAIN, "Paris.");

        assertThat(intentOfCustomer("Thủ đô của Pháp là gì")).isEqualTo(ChatIntent.OUT_OF_DOMAIN);
    }
}
