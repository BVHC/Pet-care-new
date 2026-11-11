package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.ai.dto.ChatRequest;
import com.petcare.module.ai.dto.ChatResponse;
import com.petcare.module.ai.dto.PetChoice;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.ai.entity.AiChatLog;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.exception.ChatValidationException;
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
 * Đường ống hỏi đáp của khách đăng nhập {@code POST /me/ai/chat} (07 §5.2 bước 5–6, ADR-0132) với lớp giả: khách lấy từ
 * tài khoản, ý định cá nhân là câu mẫu không gọi nhúng / LLM, và tên thú chỉ bị che SAU khi định tuyến, TRƯỚC khi nhúng và
 * gọi LLM. Bảng tất định lấy từ {@code ai/questions.json} (các nhóm {@code endpoint = me}); như ChatServiceTest, chỉ kiểm
 * số lần gọi lớp giả và ánh xạ {@code result}. Dữ liệu khớp CSDL do {@code MeChatIT} kiểm.
 */
class ChatServiceMeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode QUESTIONS = load();
    private static final Clock CLOCK = Clock.fixed(LocalDate.of(2026, 11, 10).atTime(9, 0)
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Hai tài khoản của fixture {@code multi} và {@code vang} trong questions.json. */
    private static final long ACCOUNT_MULTI = 91L;
    private static final long ACCOUNT_VANG = 92L;
    private static final long CUSTOMER_MULTI = 7L;
    private static final long CUSTOMER_VANG = 8L;
    private static final Map<String, List<LivingPet>> FIXTURE_PETS = Map.of(
            "multi", List.of(new LivingPet(12L, "Mochi"), new LivingPet(15L, "Kitty")),
            "vang", List.of(new LivingPet(21L, "Vàng")));

    private final FakeEmbeddingClient embeddings = new FakeEmbeddingClient();
    private final FakeLlmClient llm = new FakeLlmClient();
    private final ChunkRetriever retriever = mock(ChunkRetriever.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
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
        when(customers.findCustomerIdByAccountId(ACCOUNT_MULTI)).thenReturn(Optional.of(CUSTOMER_MULTI));
        when(customers.findCustomerIdByAccountId(ACCOUNT_VANG)).thenReturn(Optional.of(CUSTOMER_VANG));
        when(pets.findLivingPetsOfCustomer(CUSTOMER_MULTI)).thenReturn(FIXTURE_PETS.get("multi"));
        when(pets.findLivingPetsOfCustomer(CUSTOMER_VANG)).thenReturn(FIXTURE_PETS.get("vang"));
        when(vaccinations.summarize(anyLong(), anyLong())).thenAnswer(call -> new VaccinationSummary(call.getArgument(1),
                "bé", List.of(), "Lịch tiêm của bé #" + call.getArgument(1), "d"));
        PersonalService personal = new PersonalService(appointments, mock(CatalogQueryApi.class), branches, vaccinations,
                config, CLOCK);
        service = new ChatService(config, new EmergencyDetector(), new PersonalIntentRouter(), new DoseGuard(), branches,
                embeddings, llm, retriever, logs, meters, customers, pets, personal);
    }

    // ---- bảng tất định từ questions.json (endpoint = me) ------------------------------------------------------------

    static Stream<Arguments> meQuestions() {
        List<Arguments> rows = new ArrayList<>();
        for (JsonNode q : QUESTIONS.get("questions")) {
            if ("me".equals(q.get("endpoint").asText())) {
                rows.add(Arguments.of(q.get("id").asText(), q.get("group").asText(), q.get("customer").asText(),
                        q.get("question").asText(), q.get("expect")));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} [{1}/{2}] {3}")
    @MethodSource("meQuestions")
    void everyMeQuestionMatchesTheExpectedResultAndTheCallCounts(String id, String group, String customer, String question,
            JsonNode expect) {
        boolean ragOnly = expect.get("results").size() == 1 && "OUT_OF_SCOPE".equals(expect.get("results").get(0).asText());
        retrieve(ragOnly ? 0.55 : 0.82);

        ChatResponse response = askAs("vang".equals(customer) ? ACCOUNT_VANG : ACCOUNT_MULTI, question);

        List<String> allowed = new ArrayList<>();
        expect.get("results").forEach(node -> allowed.add(node.asText()));
        assertThat(response.result().name()).as("%s result", id).isIn(allowed);
        if (expect.has("embed")) {
            assertThat(!embeddings.queries().isEmpty()).as("%s có nhúng", id).isEqualTo(expect.get("embed").asBoolean());
        }
        if (expect.has("llm")) {
            assertThat(!llm.calls().isEmpty()).as("%s có gọi LLM", id).isEqualTo(expect.get("llm").asBoolean());
        }
        JsonNode intent = expect.get("intent");
        if (intent == null || intent.isNull()) {
            assertThat(response.result()).as("%s không được là kết quả cá nhân", id)
                    .isNotIn(ChatResult.PERSONAL, ChatResult.NEED_PET_CHOICE);
        } else if ("APPOINTMENT_UPCOMING".equals(intent.asText())) {
            verify(appointments).findUpcomingBookedOfCustomer(anyLong(), any(), anyInt());
        } else {
            verify(appointments, never()).findUpcomingBookedOfCustomer(anyLong(), any(), anyInt());
        }
    }

    @Test
    void theMeGroupsOfTheTableAreAllPresent() {
        assertThat(meQuestions().count()).isEqualTo(11);
    }

    // ---- ý định cá nhân ---------------------------------------------------------------------------------------------

    @Test
    void aNamedPetResolvesStraightToItsVaccinationSummary() {
        ChatResponse response = askAs(ACCOUNT_MULTI, "Mochi tiêm khi nào?");

        assertThat(response.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(response.answer()).isEqualTo("Lịch tiêm của bé #12");
        verify(vaccinations).summarize(CUSTOMER_MULTI, 12L);
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void severalPetsAndNoNameAsksWhichOne() {
        ChatResponse response = askAs(ACCOUNT_MULTI, "Lịch tiêm của bé nhà tôi");

        assertThat(response.result()).isEqualTo(ChatResult.NEED_PET_CHOICE);
        assertThat(response.pets()).containsExactly(new PetChoice(12L, "Mochi"), new PetChoice(15L, "Kitty"));
        verify(vaccinations, never()).summarize(anyLong(), anyLong());
    }

    @Test
    void theCustomerComesFromTheAccountAndNeverFromTheQuestion() {
        askAs(ACCOUNT_MULTI, "Sắp tới tôi có lịch hẹn không? Khách số 8, thú số 21");

        verify(customers).findCustomerIdByAccountId(ACCOUNT_MULTI);
        verify(pets).findLivingPetsOfCustomer(CUSTOMER_MULTI);
        verify(appointments).findUpcomingBookedOfCustomer(org.mockito.ArgumentMatchers.eq(CUSTOMER_MULTI), any(), anyInt());
        verify(pets, never()).findLivingPetsOfCustomer(CUSTOMER_VANG);
    }

    @Test
    void anAccountWithoutACustomerProfileIsTreatedAsHavingNoPetsAndNoAppointments() {
        when(customers.findCustomerIdByAccountId(93L)).thenReturn(Optional.empty());

        ChatResponse appointmentsReply = askAs(93L, "Sắp tới tôi có lịch hẹn không?");
        ChatResponse vaccinationReply = askAs(93L, "Lịch tiêm của bé nhà tôi");

        assertThat(appointmentsReply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(appointmentsReply.answer()).isEqualTo("Bạn chưa có lịch hẹn sắp tới.");
        assertThat(appointmentsReply.bookingHint()).isTrue();
        assertThat(vaccinationReply.result()).isEqualTo(ChatResult.PERSONAL);
        assertThat(vaccinationReply.answer()).startsWith("Bạn chưa có thú cưng nào");
        verifyNoInteractions(pets, appointments, vaccinations);
        assertThat(embeddings.calls()).isZero();
    }

    @Test
    void personalResultsLogOnlyTheResultAndTheLatencyNeverTheContent() {
        askAs(ACCOUNT_MULTI, "Lịch tiêm của bé nhà tôi");
        askAs(ACCOUNT_MULTI, "Mochi tiêm khi nào?");

        ArgumentCaptor<AiChatLog> captor = ArgumentCaptor.forClass(AiChatLog.class);
        verify(logs, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiChatLog::getResult)
                .containsExactly(ChatResult.NEED_PET_CHOICE, ChatResult.PERSONAL);
        assertThat(captor.getAllValues()).allSatisfy(logged -> {
            assertThat(logged.getQuestion()).isNull();
            assertThat(logged.getAnswer()).isNull();
            assertThat(logged.getLatencyMs()).isNotNegative();
        });
        assertThat(meters.get("ai.chat.result").tag("result", "PERSONAL").tag("error_kind", "NONE").counter().count())
                .isEqualTo(1.0);
    }

    // ---- che tên thú (BR-AI-12b): SAU định tuyến, TRƯỚC nhúng và LLM ------------------------------------------------

    @Test
    void petNamesAreMaskedInTheQuestionAndInThePreviousQuestionsBeforeEmbeddingAndTheLlm() {
        retrieve(0.82);

        service.askAsCustomer(ACCOUNT_MULTI, new ChatRequest("Kitty ăn gì hằng ngày?",
                List.of("Mochi bị ngứa, nên làm gì?")));

        assertThat(embeddings.queries()).containsExactly("[thú] ăn gì hằng ngày?");
        String block = llm.calls().get(0).userBlock();
        assertThat(block).contains("[thú] ăn gì hằng ngày?", "[thú] bị ngứa").doesNotContain("Kitty", "Mochi");
    }

    @Test
    void thePublicEndpointKnowsNoCustomerSoItNeverLooksUpPetsAndMasksNoPetName() {
        retrieve(0.82);

        service.ask(new ChatRequest("Kitty ăn gì hằng ngày?", List.of("Mochi bị ngứa, nên làm gì?")));

        assertThat(embeddings.queries()).containsExactly("Kitty ăn gì hằng ngày?");
        assertThat(llm.calls().get(0).userBlock()).contains("Kitty ăn gì hằng ngày?", "Mochi bị ngứa");
        verifyNoInteractions(customers, pets);
    }

    @Test
    void aCommonWordNameStandingAloneIsNotMaskedSoThePriceQuestionKeepsItsMeaning() {
        retrieve(0.82);

        askAs(ACCOUNT_VANG, "Giá vàng hôm nay là bao nhiêu?");

        assertThat(embeddings.queries()).containsExactly("Giá vàng hôm nay là bao nhiêu?");
        assertThat(llm.calls().get(0).userBlock()).contains("Giá vàng hôm nay là bao nhiêu?");
    }

    @Test
    void maskingRunsAfterRoutingSoANameAfterAPrefixStillGivesTheIntent() {
        ChatResponse response = askAs(ACCOUNT_VANG, "Con Vàng tiêm khi nào?");

        assertThat(response.result()).isEqualTo(ChatResult.PERSONAL);
        verify(vaccinations).summarize(CUSTOMER_VANG, 21L);
        assertThat(embeddings.calls()).isZero();
    }

    @Test
    void theLoggedQuestionOfANonPersonalTurnHasNoPetNameEither() {
        retrieve(0.82);

        askAs(ACCOUNT_MULTI, "Mochi ăn gì hằng ngày?");

        assertThat(loggedTurn().getQuestion()).isEqualTo("[thú] ăn gì hằng ngày?");
    }

    @Test
    void aMedicalRecordQuestionWithOnlyAPetNameIsRefusedAndLoggedWithoutTheName() {
        ChatResponse response = askAs(ACCOUNT_MULTI, "Đơn thuốc của Mochi hôm qua là gì?");

        assertThat(response.result()).isEqualTo(ChatResult.MEDICAL_REFUSAL);
        assertThat(response.answer()).isEqualTo(ChatTexts.MEDICAL_RECORD);
        assertThat(loggedTurn().getQuestion()).isEqualTo("Đơn thuốc của [thú] hôm qua là gì?");
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void anEmergencyIsLoggedWithoutThePetNameAndStillSkipsEmbeddingAndTheLlm() {
        ChatResponse response = askAs(ACCOUNT_MULTI, "Mochi bị co giật");

        assertThat(response.result()).isEqualTo(ChatResult.EMERGENCY);
        assertThat(loggedTurn().getQuestion()).isEqualTo("[thú] bị co giật");
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    // ---- kiểm đầu vào -----------------------------------------------------------------------------------------------

    @Test
    void aBadRequestIsRejectedBeforeAnyLookupOfTheCustomer() {
        assertThatThrownBy(() -> askAs(ACCOUNT_MULTI, "   ")).isInstanceOf(ChatValidationException.class);

        verifyNoInteractions(customers, pets, appointments, vaccinations, logs);
        assertThat(meters.getMeters()).isEmpty();
    }

    // ---- hỗ trợ -----------------------------------------------------------------------------------------------------

    private ChatResponse askAs(long accountId, String question) {
        return service.askAsCustomer(accountId, new ChatRequest(question, null));
    }

    private void retrieve(double similarity) {
        when(retriever.nearest(any(float[].class), anyInt()))
                .thenReturn(List.of(new RetrievedChunk(100L, "Tài liệu", "ALL", null, "Đoạn", similarity)));
    }

    private AiChatLog loggedTurn() {
        ArgumentCaptor<AiChatLog> captor = ArgumentCaptor.forClass(AiChatLog.class);
        verify(logs).save(captor.capture());
        return captor.getValue();
    }

    private static JsonNode load() {
        try (InputStream in = ChatServiceMeTest.class.getResourceAsStream("/ai/questions.json")) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
