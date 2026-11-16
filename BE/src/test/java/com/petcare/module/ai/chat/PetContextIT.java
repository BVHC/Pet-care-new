package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Khối {@code THÔNG TIN THÚ} qua HTTP, trên Postgres thật (BR-AI-32, docs/adr/0135 D2, D4, "Kiểm thử dự kiến"), với nhúng
 * và LLM giả: {@code FakeLlmClient} bắt lời nhắc thật mà BE gửi đi. Phần bắt buộc là cách ly: hai khách, mỗi khách hai thú
 * (khác loài, giống, cân nặng, lịch tiêm, lịch sử dịch vụ); khách này không bao giờ nhận dữ liệu của khách kia, qua mọi kiểu
 * câu hỏi. Công tắc kiến thức chung được bật để LLM luôn được gọi mà không cần tài liệu. Era <b>năm 2048</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PetContextIT.TestBeans.class})
class PetContextIT {

    private static final String TAG = "PetContextIT";
    private static final Instant T0 = Instant.parse("2048-03-10T03:00:00Z");
    private static final String ME_CHAT = "/api/me/ai/chat";
    private static final String PUBLIC_CHAT = "/api/public/ai/chat";
    private static final String NUTRITION_BY_NAME = "%s nên ăn mấy bữa một ngày?";
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
    @Autowired
    private SystemConfigService configService;

    private AiReadFixtures fx;
    private AppointmentItSupport support;
    private final List<Long> vaccineTypes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        llm.reset();
        fx = new AiReadFixtures(jdbc, transactionManager);
        support = new AppointmentItSupport(jdbc);
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        setSwitch("ai.chat.general_knowledge_enabled", true);
        setSwitch("ai.chat.pet_context_enabled", true);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        fx.cleanup();
        vaccineTypes.forEach(id -> jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id));
        support.cleanUp();
        setSwitch("ai.chat.general_knowledge_enabled", false);
        setSwitch("ai.chat.pet_context_enabled", false);
    }

    // ================================================================ cách ly giữa hai khách

    @Test
    void eachCustomerOnlyEverGetsTheirOwnPetsDataInThePromptSentToTheProvider() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        pet(a, "Mochi", "DOG", "Poodle", "4.23", true);
        pet(a, "Kitty", "CAT", "Anh lông ngắn", "2.10", false);
        pet(b, "Rocky", "DOG", "Husky Siberia", "25.00", false);
        pet(b, "Luna", "CAT", "Ba Tư", "5.50", true);

        ask(a, NUTRITION_BY_NAME.formatted("Mochi"));
        ask(b, NUTRITION_BY_NAME.formatted("Rocky"));
        ask(a, NUTRITION_BY_NAME.formatted("Kitty"));
        ask(b, NUTRITION_BY_NAME.formatted("Luna"));

        List<FakeLlmClient.Call> calls = llm.calls();
        assertThat(calls).hasSize(4);
        assertThat(petBlock(calls.get(0))).contains("Giống: Poodle", "Cân nặng gần nhất: 4,2 kg", "Đã triệt sản: có");
        assertThat(petBlock(calls.get(1))).contains("Giống: Husky Siberia", "Cân nặng gần nhất: 25,0 kg", "Đã triệt sản: chưa");
        assertThat(petBlock(calls.get(2))).contains("Loài: mèo", "Giống: Anh lông ngắn", "Cân nặng gần nhất: 2,1 kg");
        assertThat(petBlock(calls.get(3))).contains("Giống: Ba Tư", "Cân nặng gần nhất: 5,5 kg");
        assertThat(calls.get(0).userBlock() + calls.get(2).userBlock()).as("khách A")
                .doesNotContain("Husky", "Siberia", "Ba Tư", "25,0", "5,5 kg");
        assertThat(calls.get(1).userBlock() + calls.get(3).userBlock()).as("khách B")
                .doesNotContain("Poodle", "Anh lông ngắn", "4,2 kg", "2,1 kg");
        assertThat(calls).allSatisfy(call -> assertThat(call.userBlock()).as("tên thú vẫn bị che")
                .doesNotContain("Mochi", "Kitty", "Rocky", "Luna"));
    }

    @Test
    void aQuestionAboutTheOtherCustomersPetNeverBringsTheirDataToAnyCustomer() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        Customer c = customer("Khách C");
        pet(a, "Mochi", "DOG", "Poodle", "4.23", true);
        pet(a, "Kitty", "CAT", "Anh lông ngắn", "2.10", false);
        pet(b, "Rocky", "DOG", "Husky Siberia", "25.00", false);
        pet(c, "Bơ", "CAT", "Munchkin", "3.30", true);

        ask(a, NUTRITION_BY_NAME.formatted("Rocky"));
        ask(a, "Con Husky Siberia 25 kg của Rocky nên ăn gì?");
        ask(c, NUTRITION_BY_NAME.formatted("Rocky"));

        List<FakeLlmClient.Call> calls = llm.calls();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).userBlock()).as("A có hai thú và không nêu tên thú nào của mình: không có khối")
                .doesNotContain("THÔNG TIN THÚ");
        assertThat(calls.get(1).userBlock()).doesNotContain("THÔNG TIN THÚ");
        assertThat(petBlock(calls.get(2))).as("C chỉ có một thú: khối là của chính C, không phải của B")
                .contains("Giống: Munchkin").doesNotContain("Husky", "Siberia", "25,0");
    }

    @Test
    void aPetThatMovedToAnotherCustomerOrDiedStopsProducingABlockOnTheVeryNextTurn() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        long mochi = pet(a, "Mochi", "DOG", "Poodle", "4.23", true);

        ask(a, "Chó nên ăn mấy bữa một ngày?");
        fx.movePet(mochi, b.customerId());
        ask(a, "Chó nên ăn mấy bữa một ngày?");
        fx.movePet(mochi, a.customerId());
        ask(a, "Chó nên ăn mấy bữa một ngày?");
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", today().minusDays(1), mochi);
        ask(a, "Chó nên ăn mấy bữa một ngày?");

        List<FakeLlmClient.Call> calls = llm.calls();
        assertThat(calls).hasSize(4);
        assertThat(calls.get(0).userBlock()).contains("Giống: Poodle");
        assertThat(calls.get(1).userBlock()).as("đã chuyển chủ").doesNotContain("THÔNG TIN THÚ");
        assertThat(calls.get(2).userBlock()).as("quay lại chủ cũ").contains("Giống: Poodle");
        assertThat(calls.get(3).userBlock()).as("đã mất").doesNotContain("THÔNG TIN THÚ");
    }

    // ================================================================ chọn trường theo ý định, dữ liệu thật

    @Test
    void aVaccinationQuestionSendsTheLatestDosePerVaccineAsMonthAndYearOnly() {
        Customer a = customer("Khách A");
        long mochi = pet(a, "Mochi", "DOG", "Poodle", "4.23", true);
        long vaccineType = vaccineType("DOG");
        fx.protocol("DOG", vaccineType, 1, true);
        fx.vaccination(mochi, vaccineType, 1, today().minusMonths(13), today().minusDays(3), false);
        String vaccineName = jdbc.queryForObject("SELECT name FROM vaccine_types WHERE id = ?", String.class, vaccineType);

        ask(a, "Chó trưởng thành thường nên tiêm nhắc lại bao lâu một lần?");

        String block = petBlock(llm.calls().get(0));
        assertThat(block).contains("Loài: chó", "Nhóm tuổi: trưởng thành",
                "Tiêm phòng: " + vaccineName + " (đã tiêm tháng 2/2047, quá hạn).");
        assertThat(block).doesNotContain("Giống", "Cân nặng", "triệt sản", "Dịch vụ gần đây")
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}").doesNotContainPattern("\\d{1,2}/\\d{1,2}/\\d{4}");
    }

    @Test
    void aCareQuestionSendsTheServiceGroupAndMonthButNeverTheServiceName() {
        Customer a = customer("Khách A");
        long mochi = pet(a, "Mochi", "DOG", "Poodle", "4.23", true);
        long service = fx.service("GROOMING", true);
        String serviceName = jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, service);
        Instant at = T0.minus(20, ChronoUnit.DAYS);
        long order = fx.order(a.customerId(), fx.visit(a.customerId(), mochi, "COMPLETED", at), "PAID", at);
        fx.serviceLine(order, service);

        ask(a, "Chó nên chải lông bao lâu một lần?");

        String block = petBlock(llm.calls().get(0));
        assertThat(block).contains("Giống: Poodle", "Dịch vụ gần đây: thẩm mỹ tháng 2/2048.");
        assertThat(block).doesNotContain(serviceName).doesNotContain("Cân nặng", "Tiêm phòng");
    }

    // ================================================================ công tắc, endpoint công khai, nhật ký

    @Test
    void theSwitchTakesEffectOnTheNextTurnAndTheLogFlagFollowsWhatWasSent() {
        Customer a = customer("Khách A");
        pet(a, "Mochi", "DOG", "Poodle", "4.23", true);

        setSwitch("ai.chat.pet_context_enabled", false);
        ask(a, "Chó nên ăn mấy bữa một ngày?");
        setSwitch("ai.chat.pet_context_enabled", true);
        ask(a, "Chó nên ăn mấy bữa một ngày?");
        setSwitch("ai.chat.pet_context_enabled", false);
        ask(a, "Chó nên ăn mấy bữa một ngày?");

        List<FakeLlmClient.Call> calls = llm.calls();
        assertThat(calls.get(0).userBlock()).doesNotContain("THÔNG TIN THÚ");
        assertThat(calls.get(1).userBlock()).contains("THÔNG TIN THÚ");
        assertThat(calls.get(2).userBlock()).doesNotContain("THÔNG TIN THÚ");
        assertThat(usedFlags()).containsExactly(false, true, false);
    }

    @Test
    void thePublicEndpointNeverGetsABlockEvenWithTheSwitchOn() {
        Customer a = customer("Khách A");
        pet(a, "Mochi", "DOG", "Poodle", "4.23", true);

        ResponseEntity<Map<String, Object>> response = http.exchange(URI.create(http.getRootUri() + PUBLIC_CHAT),
                HttpMethod.POST, new HttpEntity<>(Map.of("question", "Chó nên ăn mấy bữa một ngày?"), headers(null)), MAP);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(llm.calls()).hasSize(1);
        assertThat(llm.calls().get(0).userBlock()).doesNotContain("THÔNG TIN THÚ");
        assertThat(llm.calls().get(0).systemPrompt()).doesNotContain("THÔNG TIN THÚ");
        assertThat(usedFlags()).containsExactly(false);
    }

    @Test
    void theLogHoldsTheFlagButNothingOfTheBlockAndANoDocumentTurnLogsFalse() {
        Customer a = customer("Khách A");
        pet(a, "Mochi", "DOG", "Poodle", "4.23", true);

        ask(a, NUTRITION_BY_NAME.formatted("Mochi"));
        setSwitch("ai.chat.general_knowledge_enabled", false);
        ask(a, NUTRITION_BY_NAME.formatted("Mochi"));

        assertThat(llm.calls()).as("lượt thứ hai không có tài liệu và công tắc kiến thức chung tắt: không gọi LLM").hasSize(1);
        assertThat(usedFlags()).containsExactly(true, false);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT question, answer FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        assertThat(rows).hasSize(2);
        assertThat(rows.toString()).doesNotContain("Poodle", "4,2", "triệt sản", "Loài", "Giống", "Mochi");
        assertThat(rows.get(0).get("question")).isEqualTo("[thú] nên ăn mấy bữa một ngày?");
    }

    // ================================================================ hỗ trợ

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Công tắc [CFG] đọc từ cache; sửa DB rồi nạp lại như UC10 làm sau khi commit. */
    private void setSwitch(String key, boolean on) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", String.valueOf(on), key);
        configService.reload();
    }

    private long pet(Customer owner, String name, String species, String breed, String kg, boolean neutered) {
        long pet = fx.pet(owner.customerId(), name, species, today().minusYears(3), null, breed);
        fx.sexAndNeutered(pet, "MALE", neutered);
        fx.weight(pet, kg, "2048-02-01T03:00:00Z");
        return pet;
    }

    private long vaccineType(String species) {
        long id = jdbc.queryForObject("INSERT INTO vaccine_types (name, species) VALUES (?, ?) RETURNING id", Long.class,
                "Vac " + UUID.randomUUID().toString().substring(0, 8), species);
        vaccineTypes.add(id);
        return id;
    }

    private Customer customer(String name) {
        long account = support.customerAccount("pc-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        long customerId = support.onlineCustomer(account, name);
        return new Customer(account, customerId, login(account));
    }

    private String login(long accountId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private void ask(Customer customer, String question) {
        ResponseEntity<Map<String, Object>> response = http.exchange(URI.create(http.getRootUri() + ME_CHAT),
                HttpMethod.POST, new HttpEntity<>(Map.of("question", question), headers(customer.token())), MAP);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Phần chữ của khối THÔNG TIN THÚ trong khối người dùng; thất bại rõ ràng nếu lời nhắc không có khối. */
    private static String petBlock(FakeLlmClient.Call call) {
        String userBlock = call.userBlock();
        int start = userBlock.indexOf("THÔNG TIN THÚ");
        assertThat(start).as("lời nhắc phải có khối THÔNG TIN THÚ:\n" + userBlock).isNotNegative();
        int open = userBlock.indexOf("\"\"\"\n", start) + 4;
        return userBlock.substring(open, userBlock.indexOf("\n\"\"\"", open));
    }

    private List<Boolean> usedFlags() {
        return jdbc.queryForList("SELECT used_pet_context FROM ai_chat_logs WHERE created_at >= ? ORDER BY id",
                Boolean.class, java.sql.Timestamp.from(T0));
    }
}
