package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.ai.dto.ChatRequest;
import com.petcare.module.ai.dto.ChatResponse;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.ai.entity.AiChatLog;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.repository.AiChatLogRepository;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;
import com.petcare.module.ai.service.VaccinationSummaryService;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.LivingPet;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Câu cá nhân mơ hồ ở {@code POST /me/ai/chat} hỏi mô hình một nhãn (docs/adr/0157): chỉ khi công tắc bật, khách đã đăng
 * nhập và luật BR-AI-26 không nhận mà câu có từ khóa. Nhãn cá nhân trả bằng dữ liệu DB như BR-AI-26; NONE, {@code null} và
 * lỗi mô hình đi tiếp đường cũ. Fixture như {@code ChatServiceMeTest}.
 */
class ChatIntentLlmTest {

    private static final Clock CLOCK = Clock.fixed(LocalDate.of(2026, 11, 27).atTime(9, 0)
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final long ACCOUNT_MULTI = 91L;
    private static final long ACCOUNT_ONE_PET = 92L;
    private static final long CUSTOMER_MULTI = 7L;
    private static final long CUSTOMER_ONE_PET = 8L;
    private static final String AMBIGUOUS_APPOINTMENT = "tôi có lịch hẹn ko? gọi 0912345678 nhé";

    private final FakeEmbeddingClient embeddings = new FakeEmbeddingClient();
    private final FakeLlmClient llm = new FakeLlmClient();
    private final ChunkRetriever retriever = mock(ChunkRetriever.class);
    private final AiChatLogRepository logs = mock(AiChatLogRepository.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class);
    private final VaccinationSummaryService vaccinations = mock(VaccinationSummaryService.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private ChatService service;

    @BeforeEach
    void setUp() {
        when(config.getInt(ConfigKey.AI_CHAT_MAX_QUESTION_CHARS)).thenReturn(500);
        when(config.getInt(ConfigKey.AI_CHAT_MAX_PREVIOUS_QUESTIONS)).thenReturn(3);
        when(config.getInt(ConfigKey.AI_RETRIEVAL_TOP_K)).thenReturn(5);
        when(config.getInt(ConfigKey.AI_PERSONAL_APPOINTMENTS_LIMIT)).thenReturn(5);
        when(config.getDecimal(ConfigKey.AI_RETRIEVAL_MIN_SIMILARITY)).thenReturn(new BigDecimal("0.60"));
        when(config.getBool(ConfigKey.AI_CHAT_INTENT_LLM_ENABLED)).thenReturn(true);
        when(customers.findCustomerIdByAccountId(ACCOUNT_MULTI)).thenReturn(Optional.of(CUSTOMER_MULTI));
        when(customers.findCustomerIdByAccountId(ACCOUNT_ONE_PET)).thenReturn(Optional.of(CUSTOMER_ONE_PET));
        when(pets.findLivingPetsOfCustomer(CUSTOMER_MULTI))
                .thenReturn(List.of(new LivingPet(12L, "Mochi"), new LivingPet(15L, "Kitty")));
        when(pets.findLivingPetsOfCustomer(CUSTOMER_ONE_PET)).thenReturn(List.of(new LivingPet(21L, "Bơ")));
        when(vaccinations.summarize(anyLong(), anyLong())).thenAnswer(call -> new VaccinationSummary(call.getArgument(1),
                "bé", List.of(), "Lịch tiêm của bé #" + call.getArgument(1), "d"));
        when(retriever.nearest(any(float[].class), anyInt()))
                .thenReturn(List.of(new RetrievedChunk(100L, "Tài liệu", "ALL", null, "Đoạn", 0.82)));
        PersonalService personal = new PersonalService(appointments, mock(CatalogQueryApi.class), mock(BranchQueryApi.class),
                vaccinations, config, CLOCK);
        service = new ChatService(config, new EmergencyDetector(), new PersonalIntentRouter(), new DoseGuard(),
                mock(BranchQueryApi.class), embeddings, llm, retriever, logs, meters, customers, pets, personal,
                mock(BusinessAnswerService.class), mock(PetContextBuilder.class));
    }

    @Test
    void anAmbiguousQuestionLabelledAppointmentAnswersFromTheDatabaseWithoutWritingAnAnswer() {
        llm.classifyReply("APPOINTMENT_UPCOMING");

        ChatResponse response = askAs(ACCOUNT_MULTI, AMBIGUOUS_APPOINTMENT);

        assertThat(response.result()).isEqualTo(ChatResult.PERSONAL);
        verify(appointments).findUpcomingBookedOfCustomer(eq(CUSTOMER_MULTI), any(), anyInt());
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
        assertThat(llm.classifyCalls()).hasSize(1);
        FakeLlmClient.Call call = llm.classifyCalls().get(0);
        assertThat(call.systemPrompt()).isEqualTo(ChatPrompt.INTENT_SYSTEM);
        assertThat(call.userBlock()).contains("tôi có lịch hẹn ko?", "[sđt]").doesNotContain("0912345678");
        assertThat(outcome("APPOINTMENT_UPCOMING")).isEqualTo(1.0);
    }

    @Test
    void anAmbiguousQuestionLabelledVaccinationGivesTheScheduleOfTheOnlyPet() {
        llm.classifyReply("VACCINATION_SCHEDULE");

        ChatResponse response = askAs(ACCOUNT_ONE_PET, "bé sắp phải tiêm mũi nào chưa");

        assertThat(response.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(response.answer()).isEqualTo("Lịch tiêm của bé #21");
        verify(vaccinations).summarize(CUSTOMER_ONE_PET, 21L);
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void aGeneralQuestionLabelledNoneGoesOnToTheDocuments() {
        llm.classifyReply("NONE");

        ChatResponse response = askAs(ACCOUNT_MULTI, "Chó con tiêm phòng dại lúc mấy tuần?");

        assertThat(response.result()).isEqualTo(ChatResult.ANSWERED);
        assertThat(llm.classifyCalls()).hasSize(1);
        assertThat(llm.calls()).hasSize(1);
        verify(appointments, never()).findUpcomingBookedOfCustomer(anyLong(), any(), anyInt());
        verify(vaccinations, never()).summarize(anyLong(), anyLong());
        assertThat(outcome("NONE")).isEqualTo(1.0);
    }

    @Test
    void aClassifierFailureNeverBreaksTheTurnItGoesOnToTheDocuments() {
        llm.classifyFailWith(new AiProviderException(ChatErrorKind.TIMEOUT, "quá hạn"));

        ChatResponse response = askAs(ACCOUNT_MULTI, AMBIGUOUS_APPOINTMENT);

        assertThat(response.result()).isEqualTo(ChatResult.ANSWERED);
        assertThat(llm.calls()).hasSize(1);
        verify(appointments, never()).findUpcomingBookedOfCustomer(anyLong(), any(), anyInt());
        assertThat(outcome("ERROR")).isEqualTo(1.0);
    }

    @Test
    void aNullLabelIsTreatedAsNone() {
        llm.classifyReply(null);

        ChatResponse response = askAs(ACCOUNT_MULTI, AMBIGUOUS_APPOINTMENT);

        assertThat(response.result()).isEqualTo(ChatResult.ANSWERED);
        verify(appointments, never()).findUpcomingBookedOfCustomer(anyLong(), any(), anyInt());
    }

    @Test
    void theSwitchOffKeepsTheOldRuleOnly() {
        when(config.getBool(ConfigKey.AI_CHAT_INTENT_LLM_ENABLED)).thenReturn(false);
        llm.classifyReply("APPOINTMENT_UPCOMING");

        ChatResponse response = askAs(ACCOUNT_MULTI, AMBIGUOUS_APPOINTMENT);

        assertThat(llm.classifyCalls()).isEmpty();
        assertThat(response.result()).isEqualTo(ChatResult.ANSWERED);
    }

    @Test
    void aQuestionTheRuleAlreadyRecognisesNeverAsksTheModel() {
        ChatResponse response = askAs(ACCOUNT_MULTI, "lịch hẹn của tôi");

        assertThat(response.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(llm.classifyCalls()).isEmpty();
    }

    @Test
    void aQuestionWithoutAnIntentKeywordNeverAsksTheModel() {
        askAs(ACCOUNT_MULTI, "chó bị tiêu chảy nên ăn gì");

        assertThat(llm.classifyCalls()).isEmpty();
    }

    @Test
    void thePublicEndpointNeverAsksTheModel() {
        llm.classifyReply("APPOINTMENT_UPCOMING");

        service.ask(new ChatRequest(AMBIGUOUS_APPOINTMENT, null));

        assertThat(llm.classifyCalls()).isEmpty();
    }

    @Test
    void aPersonalTurnFoundByTheModelLogsNoContent() {
        llm.classifyReply("APPOINTMENT_UPCOMING");

        askAs(ACCOUNT_MULTI, AMBIGUOUS_APPOINTMENT);

        ArgumentCaptor<AiChatLog> captor = ArgumentCaptor.forClass(AiChatLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(ChatResult.PERSONAL);
        assertThat(captor.getValue().getQuestion()).isNull();
        assertThat(captor.getValue().getAnswer()).isNull();
    }

    private ChatResponse askAs(long accountId, String question) {
        return service.askAsCustomer(accountId, new ChatRequest(question, null));
    }

    private double outcome(String value) {
        return meters.get("ai.chat.intent_llm").tag("outcome", value).counter().count();
    }
}
