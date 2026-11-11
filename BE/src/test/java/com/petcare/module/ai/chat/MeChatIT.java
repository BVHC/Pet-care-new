package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.AiReadFixtures;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * Chat cá nhân UC91 qua HTTP, trên Postgres thật (ai-v1 #2 {@code POST /me/ai/chat}, #3
 * {@code GET /me/ai/pets/{petId}/vaccination-summary}; BR-AI-11, 12b, 26…28; docs/adr/0132), với embedding và LLM giả (CI
 * không gọi mạng). Dữ liệu dựng bằng SQL; số liệu trả về đối chiếu với chính CSDL. Phần bảo mật: khách khác không thấy dữ
 * liệu, thú sau khi chuyển chủ là 404 cho chủ cũ NGAY ở lần gọi kế tiếp (không cache), thú mất là 400. Era <b>năm 2043</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MeChatIT.TestBeans.class})
class MeChatIT {

    private static final String TAG = "MeChatIT";
    private static final Instant T0 = Instant.parse("2043-03-10T03:00:00Z");
    private static final String CHAT = "/api/me/ai/chat";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }

        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    /** Một khách đã đăng nhập: tài khoản, hồ sơ khách và token. */
    private record Customer(long accountId, long customerId, String token) {
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FakeEmbeddingClient embeddings;
    @Autowired
    private FakeLlmClient llm;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AiReadFixtures fx;
    private AppointmentItSupport support;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        llm.reset();
        fx = new AiReadFixtures(jdbc, transactionManager);
        support = new AppointmentItSupport(jdbc);
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        fx.cleanup();
        support.cleanUp();
    }

    // ================================================================ #2 lịch hẹn sắp tới

    @Test
    void upcomingAppointmentsAreTheLoggedInCustomersOwnAndMatchTheDatabase() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        long mochi = fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        long kitty = fx.pet(a.customerId(), "Kitty", "CAT", today().minusYears(2), null, null);
        long rocky = fx.pet(b.customerId(), "Rocky", "DOG", today().minusYears(3), null, null);
        long service = fx.service("MEDICAL", true);
        String serviceName = jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, service);
        LocalDate d = today();
        long tomorrow = fx.appointment(a.customerId(), mochi, service, "BOOKED", d.plusDays(1), LocalTime.of(9, 0));
        long later = fx.appointment(a.customerId(), kitty, service, "BOOKED", d.plusDays(3), LocalTime.of(14, 30));
        long past = fx.appointment(a.customerId(), mochi, service, "BOOKED", d, LocalTime.of(9, 59));
        long cancelled = fx.appointment(a.customerId(), mochi, service, "CANCELLED", d.plusDays(2), LocalTime.of(9, 0));
        long strangers = fx.appointment(b.customerId(), rocky, service, "BOOKED", d.plusDays(1), LocalTime.of(9, 0));

        ResponseEntity<Map<String, Object>> response = chat(a, "Sắp tới tôi có lịch hẹn không?");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = data(response);
        assertThat(data).containsEntry("result", "PERSONAL").containsEntry("bookingHint", false);
        String answer = (String) data.get("answer");
        assertThat(answer).startsWith("Bạn có 2 lịch hẹn sắp tới:");
        assertThat(answer).contains("Mochi — " + serviceName + " — 11/03/2043 09:00",
                "Kitty — " + serviceName + " — 13/03/2043 14:30");
        assertThat(answer).contains(code(tomorrow), code(later)).doesNotContain(code(past), code(cancelled), code(strangers));
        assertThat(answer).doesNotContain("Rocky");
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
        assertThat(onlyLog()).containsEntry("result", "PERSONAL").containsEntry("question", null).containsEntry("answer", null);
    }

    @Test
    void aCustomerWithNoUpcomingAppointmentGetsTheStandardSentenceWithABookingHint() {
        Customer a = customer("Khách A");

        Map<String, Object> data = data(chat(a, "Tôi có lịch hẹn nào sắp tới không?"));

        assertThat(data).containsEntry("result", "PERSONAL").containsEntry("answer", "Bạn chưa có lịch hẹn sắp tới.")
                .containsEntry("bookingHint", true);
    }

    // ================================================================ #2 lịch tiêm

    @Test
    void aNamedPetResolvesToItsSummaryAndSeveralPetsWithoutANameAskWhichOne() {
        Customer a = customer("Khách A");
        long mochi = fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        long kitty = fx.pet(a.customerId(), "Kitty", "CAT", today().minusYears(2), null, null);
        withProtocol("DOG");

        Map<String, Object> named = data(chat(a, "Mochi tiêm khi nào?"));
        Map<String, Object> asked = data(chat(a, "Lịch tiêm của bé nhà tôi"));

        assertThat(named).containsEntry("result", "PERSONAL");
        assertThat((String) named.get("answer")).startsWith("Lịch tiêm của Mochi (chỉ tính các mũi ghi nhận tại hệ thống):");
        assertThat(asked).containsEntry("result", "NEED_PET_CHOICE").containsEntry("answer",
                "Bạn muốn xem lịch tiêm của bé nào?");
        assertThat(petsOf(asked)).as("I-1 sắp theo tên rồi id: Kitty trước Mochi")
                .containsExactly(Map.of("petId", (int) kitty, "name", "Kitty"), Map.of("petId", (int) mochi, "name", "Mochi"));
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void aCustomerWithExactlyOnePetNeedsNoName() {
        Customer a = customer("Khách A");
        fx.pet(a.customerId(), "Vàng", "DOG", today().minusYears(1), null, null);
        withProtocol("DOG");

        Map<String, Object> data = data(chat(a, "Lịch tiêm của bé nhà tôi"));

        assertThat(data).containsEntry("result", "PERSONAL");
        assertThat((String) data.get("answer")).startsWith("Lịch tiêm của Vàng");
    }

    @Test
    void aPetNameOfSomeoneElseIsNotAStrongSignSoNothingPersonalIsReturned() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        fx.pet(b.customerId(), "Rocky", "DOG", today().minusYears(3), null, null);

        Map<String, Object> data = data(chat(a, "Rocky tiêm khi nào?"));

        assertThat(data.get("result")).isNotIn("PERSONAL", "NEED_PET_CHOICE");
        assertThat((String) data.get("answer")).doesNotContain("Rocky");
    }

    @Test
    void anAccountWithoutACustomerProfileHasNoPetsAndNoAppointments() {
        long account = support.customerAccount();
        String token = login(account);

        Map<String, Object> vaccination = data(chat(token, "Lịch tiêm của bé nhà tôi"));
        Map<String, Object> appointments = data(chat(token, "Sắp tới tôi có lịch hẹn không?"));

        assertThat(vaccination).containsEntry("result", "PERSONAL");
        assertThat((String) vaccination.get("answer")).startsWith("Bạn chưa có thú cưng nào");
        assertThat(appointments).containsEntry("answer", "Bạn chưa có lịch hẹn sắp tới.").containsEntry("bookingHint", true);
        assertThat(embeddings.calls()).isZero();
    }

    // ================================================================ #3 vaccination-summary

    @Test
    void theVaccinationSummaryMatchesTheDatabaseFiveStatusesIncluded() {
        Customer a = customer("Khách A");
        long mochi = fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        long puppy = fx.pet(a.customerId(), "Bông", "DOG", today().minusWeeks(2), null, null);
        long dueSoon = AiReadFixtures.next();
        long overdue = AiReadFixtures.next();
        long notStarted = AiReadFixtures.next();
        long upToDate = AiReadFixtures.next();
        for (long type : List.of(dueSoon, overdue, notStarted, upToDate)) {
            fx.protocol("DOG", type, 1, true);
        }
        fx.protocol("DOG", dueSoon, 2, true);
        LocalDate d = today();
        fx.vaccination(mochi, dueSoon, 1, d.minusDays(20), d.plusDays(1), false);
        fx.vaccination(mochi, overdue, 1, d.minusDays(60), d.minusDays(1), false);
        fx.vaccination(mochi, overdue, 2, d.minusDays(5), d.plusDays(300), true);
        fx.vaccination(mochi, upToDate, 1, d.minusDays(30), d.plusDays(400), false);

        Map<String, Object> summary = data(get(a.token(), summaryPath(mochi)));
        Map<String, Map<String, Object>> byType = itemsByType(summary);

        assertThat(summary).containsEntry("petId", (int) mochi).containsEntry("petName", "Mochi");
        assertThat(summary.get("disclaimer")).isEqualTo("Thông tin tham khảo, không thay thế khám thú y.");
        assertThat(byType.get(String.valueOf(dueSoon))).containsEntry("status", "DUE_SOON").containsEntry("lastDoseNumber", 1)
                .containsEntry("nextDoseNumber", 2).containsEntry("nextDueDate", d.plusDays(1).toString())
                .containsEntry("lastAdministeredOn", d.minusDays(20).toString());
        assertThat(byType.get(String.valueOf(overdue))).as("mũi mới nhất đã bị thay thế thì bỏ qua")
                .containsEntry("status", "OVERDUE").containsEntry("lastDoseNumber", 1);
        assertThat(byType.get(String.valueOf(notStarted))).containsEntry("status", "NOT_STARTED")
                .containsEntry("firstDoseFromAgeWeeks", 6);
        assertThat(byType.get(String.valueOf(upToDate))).containsEntry("status", "UP_TO_DATE");

        Map<String, Object> young = data(get(a.token(), summaryPath(puppy)));
        assertThat(itemsByType(young).get(String.valueOf(notStarted))).as("2 tuần tuổi, mũi đầu từ 6 tuần")
                .containsEntry("status", "NOT_YET_ELIGIBLE").containsEntry("firstDoseFromAgeWeeks", 6);
    }

    // ================================================================ bảo mật

    @Test
    void aPetOfAnotherCustomerIsNotFoundWithTheSameMessageAsAnUnknownPet() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        long rocky = fx.pet(b.customerId(), "Rocky", "DOG", today().minusYears(3), null, null);

        ResponseEntity<Map<String, Object>> others = get(a.token(), summaryPath(rocky));
        ResponseEntity<Map<String, Object>> unknown = get(a.token(), summaryPath(AiReadFixtures.next()));

        assertThat(others.getStatusCode().value()).isEqualTo(404);
        assertThat(others.getBody()).containsEntry("errorCode", "RESOURCE_NOT_FOUND");
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(others.getBody().get("message")).isEqualTo("Không tìm thấy thú cưng #" + rocky);
    }

    @Test
    void aPetThatChangedOwnerIsNotFoundForTheFormerOwnerAtTheVeryNextCall() {
        Customer former = customer("Chủ cũ");
        Customer current = customer("Chủ mới");
        long pet = fx.pet(former.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        assertThat(get(former.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(200);

        fx.movePet(pet, current.customerId());

        assertThat(get(former.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(404);
        assertThat(get(current.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> asked = data(chat(former, "Mochi tiêm khi nào?"));
        assertThat(asked.get("result")).as("tên thú của chủ cũ không còn là dấu hiệu sở hữu")
                .isNotIn("PERSONAL", "NEED_PET_CHOICE");
    }

    @Test
    void aDeceasedPetIs400ForItsOwnerAndStill404ForAnyoneElse() {
        Customer owner = customer("Chủ");
        Customer stranger = customer("Người lạ");
        long gone = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusYears(5), today().minusDays(3), null);

        ResponseEntity<Map<String, Object>> own = get(owner.token(), summaryPath(gone));
        ResponseEntity<Map<String, Object>> other = get(stranger.token(), summaryPath(gone));

        assertThat(own.getStatusCode().value()).isEqualTo(400);
        assertThat(own.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
        assertThat((String) own.getBody().get("message")).contains("BR-KH-05");
        assertThat(other.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aDeceasedPetNoLongerAppearsInTheNameSignOrTheChoiceList() {
        Customer a = customer("Khách A");
        fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(5), today().minusDays(3), null);
        fx.pet(a.customerId(), "Kitty", "CAT", today().minusYears(2), null, null);
        withProtocol("CAT");

        Map<String, Object> data = data(chat(a, "Lịch tiêm của bé nhà tôi"));

        assertThat(data).containsEntry("result", "PERSONAL");
        assertThat((String) data.get("answer")).startsWith("Lịch tiêm của Kitty");
    }

    @Test
    void theOwnerIsCheckedOnEveryCallSoNothingIsCached() {
        Customer first = customer("Chủ một");
        Customer second = customer("Chủ hai");
        long pet = fx.pet(first.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);

        assertThat(get(first.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(200);
        fx.movePet(pet, second.customerId());
        assertThat(get(first.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(404);
        fx.movePet(pet, first.customerId());
        assertThat(get(first.token(), summaryPath(pet)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAccountWithoutACustomerProfileGets404ForAnyPet() {
        Customer owner = customer("Chủ");
        long pet = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);
        String orphan = login(support.customerAccount());

        assertThat(get(orphan, summaryPath(pet)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void theEndpointsNeedACustomerToken() {
        long pet = AiReadFixtures.next();
        String admin = login(support.staff("ADMIN", null));

        assertThat(get(null, summaryPath(pet)).getStatusCode().value()).isEqualTo(401);
        assertThat(rawPost(null, Map.of("question", "Sắp tới tôi có lịch hẹn không?")).getStatusCode().value()).isEqualTo(401);
        assertThat(get(admin, summaryPath(pet)).getStatusCode().value()).isEqualTo(403);
        assertThat(rawPost(admin, Map.of("question", "Sắp tới tôi có lịch hẹn không?")).getStatusCode().value()).isEqualTo(403);
        assertThat(embeddings.calls()).isZero();
    }

    @Test
    void aMalformedPetIdIs400AndABadChatRequestIs400BeforeAnyWork() {
        Customer a = customer("Khách A");

        ResponseEntity<Map<String, Object>> badPath = rawGetPath(a.token(), "/api/me/ai/pets/abc/vaccination-summary");
        ResponseEntity<Map<String, Object>> blank = rawPost(a.token(), Map.of("question", "   "));
        ResponseEntity<Map<String, Object>> tooLong = rawPost(a.token(), Map.of("question", "a".repeat(501)));

        assertThat(badPath.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
        assertThat(embeddings.calls()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chat_logs WHERE created_at >= ?", Integer.class,
                java.sql.Timestamp.from(T0))).isZero();
    }

    // ================================================================ che tên thú (BR-AI-12b)

    @Test
    void petNamesNeverReachTheEmbeddingOrTheLogOfARagTurn() {
        Customer a = customer("Khách A");
        fx.pet(a.customerId(), "Kitty", "CAT", today().minusYears(2), null, null);

        Map<String, Object> data = data(post(a.token(), Map.of("question", "Kitty ăn gì hằng ngày?",
                "previousQuestions", List.of("Kitty bị ngứa, nên làm gì?"))));

        assertThat(data.get("result")).isNotIn("PERSONAL", "NEED_PET_CHOICE");
        assertThat(embeddings.queries()).containsExactly("[thú] ăn gì hằng ngày?");
        assertThat(onlyLog()).containsEntry("question", "[thú] ăn gì hằng ngày?");
    }

    @Test
    void aPetNamedLikeACommonWordIsLeftAloneInAPriceQuestion() {
        Customer a = customer("Khách A");
        fx.pet(a.customerId(), "Vàng", "DOG", today().minusYears(1), null, null);

        data(chat(a, "Giá vàng hôm nay là bao nhiêu?"));

        assertThat(embeddings.queries()).containsExactly("Giá vàng hôm nay là bao nhiêu?");
        assertThat(onlyLog()).containsEntry("question", "Giá vàng hôm nay là bao nhiêu?");
    }

    @Test
    void aMedicalRecordQuestionWithOnlyAPetNameIsRefusedAndLoggedWithoutTheName() {
        Customer a = customer("Khách A");
        fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(1), null, null);

        Map<String, Object> data = data(chat(a, "Đơn thuốc của Mochi hôm qua là gì?"));

        assertThat(data).containsEntry("result", "MEDICAL_REFUSAL");
        assertThat(onlyLog()).containsEntry("question", "Đơn thuốc của [thú] hôm qua là gì?");
        assertThat(embeddings.calls()).isZero();
    }

    // ================================================================ hỗ trợ

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Customer customer(String name) {
        long account = support.customerAccount("me-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        long customerId = support.onlineCustomer(account, name);
        return new Customer(account, customerId, login(account));
    }

    private String login(long accountId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    /** DB của IT không có phác đồ seed: không có dòng nào thì câu mẫu là "Chưa có phác đồ…" chứ không phải lịch tiêm. */
    private void withProtocol(String species) {
        fx.protocol(species, AiReadFixtures.next(), 1, true);
    }

    private String code(long appointmentId) {
        return jdbc.queryForObject("SELECT code FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private static String summaryPath(long petId) {
        return "/api/me/ai/pets/" + petId + "/vaccination-summary";
    }

    private ResponseEntity<Map<String, Object>> chat(Customer customer, String question) {
        return post(customer.token(), Map.of("question", question));
    }

    private ResponseEntity<Map<String, Object>> chat(String token, String question) {
        return post(token, Map.of("question", question));
    }

    private ResponseEntity<Map<String, Object>> post(String token, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> response = rawPost(token, body);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        return response;
    }

    private ResponseEntity<Map<String, Object>> rawPost(String token, Map<String, Object> body) {
        return http.exchange(URI.create(http.getRootUri() + CHAT), HttpMethod.POST, new HttpEntity<>(body, headers(token)),
                MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String token, String path) {
        return rawGetPath(token, path);
    }

    private ResponseEntity<Map<String, Object>> rawGetPath(String token, String path) {
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers(token)), MAP);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).as(String.valueOf(response.getBody())).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> petsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("pets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> itemsByType(Map<String, Object> summary) {
        return ((List<Map<String, Object>>) summary.get("items")).stream()
                .collect(Collectors.toMap(item -> String.valueOf(item.get("vaccineTypeId")), item -> item, (x, y) -> x,
                        HashMap::new));
    }

    /** Dòng nhật ký duy nhất của era này. */
    private Map<String, Object> onlyLog() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT result, error_kind, question, answer, latency_ms FROM ai_chat_logs WHERE created_at >= ?""",
                java.sql.Timestamp.from(T0));
        assertThat(rows).as("số dòng nhật ký").hasSize(1);
        return rows.get(0);
    }
}
