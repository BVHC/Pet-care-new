package com.petcare.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Gợi ý cá nhân hóa UC92 qua HTTP, trên Postgres thật với các {@code *QueryApi} thật của AI-0 (ai-v1 #4
 * {@code GET /me/ai/pets/{petId}/recommendations}; BR-AI-18…23, 07 §7; docs/adr/0133). Embedding và LLM giả để kiểm
 * <b>không bao giờ</b> được gọi (UC92 không dùng LLM). Bộ nhớ đệm là bean thật (10 phút): kiểm quyền sở hữu sau khi chuyển
 * chủ và khóa cache theo cân nặng đều phải đúng ngay cả khi cache đang nóng. Chỉ khẳng định trên id do chính test tạo.
 * Era <b>năm 2044</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RecommendationIT.TestBeans.class})
class RecommendationIT {

    private static final String TAG = "RecommendationIT";
    private static final Instant T0 = Instant.parse("2044-03-10T03:00:00Z");
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
    private long branch;
    private final List<Long> profiledServices = new ArrayList<>();
    private final List<Long> profiledProducts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        llm.reset();
        fx = new AiReadFixtures(jdbc, transactionManager);
        support = new AppointmentItSupport(jdbc);
        branch = fx.branch(TAG + " ", "ACTIVE", false);
    }

    @AfterEach
    void tearDown() {
        profiledServices.forEach(id -> jdbc.update("DELETE FROM ai_item_profiles WHERE service_id = ?", id));
        profiledProducts.forEach(id -> jdbc.update("DELETE FROM ai_item_profiles WHERE product_id = ?", id));
        fx.cleanup();
        support.cleanUp();
    }

    // ================================================================ ví dụ tính tay 07 §7.3

    /**
     * Mochi: chó poodle, 20 tuần, 3,2 kg. Cùng tỉ lệ 18/30 và 15/30 của §7.3 nhưng thu nhỏ còn 6/10 và 5/10 (cùng P = 0,6 và
     * 0,5) để khỏi dựng 63 lượt khám; dịch vụ "Cạo vôi răng" đóng vai mục có 10/10 làm c_max.
     */
    @Test
    void workedExampleThroughTheRealQueryApisGives92ThenTheNeutralItemsAndNeverCallsTheProviders() {
        Customer owner = customer("Chủ Mochi");
        Customer other = customer("Chủ khác");
        long mochi = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, "Poodle");
        fx.weight(mochi, "3.2", "2044-03-01T00:00:00Z");
        long bath = groomingService("Tắm & vệ sinh chó nhỏ");
        long bigCut = groomingService("Cắt tỉa lông chó lớn");
        long spa = groomingService("Spa thư giãn");
        long dental = groomingService("Cạo vôi răng");
        profileOfService(bath, "DOG", 12, null, "1", "8", "poodle", 30);
        profileOfService(bigCut, "DOG", null, null, "15", null, null, null);
        // Cohort (DOG, JUNIOR): Mochi dùng "Tắm" 40 ngày trước + 5 thú con khác; 5 lượt "Spa"; 10 lượt "Cạo vôi răng".
        completedVisit(owner.customerId(), mochi, bath, today().minusDays(40));
        for (int i = 0; i < 5; i++) {
            completedVisit(other.customerId(), puppyOf(other), bath, today().minusDays(10));
            completedVisit(other.customerId(), puppyOf(other), spa, today().minusDays(10));
        }
        for (int i = 0; i < 10; i++) {
            completedVisit(other.customerId(), puppyOf(other), dental, today().minusDays(10));
        }

        Map<String, Object> data = data(get(owner.token(), path(mochi, "")));

        List<Map<String, Object>> items = items(data);
        assertThat(ids(items)).as("92,0 → 47,0 → 37,0; \"Cắt tỉa lông chó lớn\" bị loại (3,2 kg < 15 kg)")
                .containsExactly(bath, dental, spa);
        assertThat(items).extracting(i -> String.valueOf(i.get("score"))).containsExactly("92.0", "47.0", "37.0");
        assertThat(items.get(0)).containsEntry("group", "GROOMING").containsEntry("itemType", "SERVICE")
                .containsEntry("priceIsFrom", true).containsEntry("price", 120000);
        assertThat(reasons(items.get(0))).contains("Phù hợp cân nặng 1–8 kg", "Đã 40 ngày kể từ lần dùng gần nhất",
                "Nhiều chủ chó con chọn");
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void changingTheWeightChangesTheListEvenWhileTheCacheIsWarm() {
        Customer owner = customer("Chủ Mochi");
        long mochi = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, "Poodle");
        fx.weight(mochi, "3.2", "2044-03-01T00:00:00Z");
        long small = groomingService("Cho chó nhỏ");
        long big = groomingService("Cho chó lớn");
        profileOfService(small, "DOG", null, null, "1", "8", null, null);
        profileOfService(big, "DOG", null, null, "15", null, null, null);

        List<Long> light = ids(items(data(get(owner.token(), path(mochi, "")))));
        List<Long> lightAgain = ids(items(data(get(owner.token(), path(mochi, "")))));
        fx.weight(mochi, "30", "2044-03-08T00:00:00Z");
        List<Long> heavy = ids(items(data(get(owner.token(), path(mochi, "")))));

        assertThat(light).contains(small).doesNotContain(big);
        assertThat(lightAgain).isEqualTo(light);
        assertThat(heavy).as("30 kg: mục chó lớn xuất hiện, mục ≤ 8 kg bị loại").contains(big).doesNotContain(small);
    }

    // ================================================================ BR-AI-18, 19

    @Test
    void neverRecommendsDrugsVaccinesMedicalServicesOrUnavailableItems() {
        Customer owner = customer("Chủ");
        long pet = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, null);
        long groom = groomingService("Tắm");
        long notEnabled = fx.service("GROOMING", true);
        long medical = fx.service("MEDICAL", true);
        fx.branchService(branch, medical, true);
        long goods = stockedProduct("GOODS", false);
        long noStock = fx.product("GOODS", false, true);
        long drug = stockedProduct("DRUG", true);
        long otcDrug = stockedProduct("DRUG", false);
        long vaccine = stockedProduct("VACCINE", false);

        Map<String, Object> data = data(get(owner.token(), path(pet, "?limit=12")));

        // Dịch vụ và sản phẩm có hai dãy id riêng nên so từng loại, không so lẫn.
        List<Map<String, Object>> shown = items(data);
        assertThat(idsOf(shown, "SERVICE")).contains(groom).doesNotContain(notEnabled, medical);
        assertThat(idsOf(shown, "PRODUCT")).contains(goods).doesNotContain(noStock, drug, otcDrug, vaccine);
    }

    @Test
    void kennelsMustMatchTheSpeciesAndTheWeightOfThePet() {
        Customer owner = customer("Chủ");
        long pet = fx.pet(owner.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, null);
        fx.weight(pet, "3.2", "2044-03-01T00:00:00Z");
        long fits = kennel("DOG", "5.00");
        long exact = kennel("DOG", "3.20");
        long tooSmall = kennel("DOG", "3.00");
        long forCats = kennel("CAT", "10.00");

        List<Map<String, Object>> items = items(data(get(owner.token(), path(pet, "?group=BOARDING&limit=12"))));

        assertThat(ids(items)).contains(fits, exact).doesNotContain(tooSmall, forCats);
        assertThat(items).allSatisfy(i -> assertThat(i).containsEntry("group", "BOARDING").containsEntry("priceUnit", "đêm"));
    }

    // ================================================================ BR-AI-11, 18: quyền

    @Test
    void aPetThatChangedOwnerIsNotFoundForTheFormerOwnerEvenWithAWarmCache() {
        Customer former = customer("Chủ cũ");
        Customer current = customer("Chủ mới");
        long pet = fx.pet(former.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, null);
        groomingService("Tắm");
        assertThat(get(former.token(), path(pet, "")).getStatusCode().value()).isEqualTo(200);

        fx.movePet(pet, current.customerId());

        assertThat(get(former.token(), path(pet, "")).getStatusCode().value()).isEqualTo(404);
        assertThat(get(current.token(), path(pet, "")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aPetOfAnotherCustomerAndAnUnknownPetAreBothNotFoundAndADeceasedPetIsRejectedForItsOwnerOnly() {
        Customer a = customer("Khách A");
        Customer b = customer("Khách B");
        long rocky = fx.pet(b.customerId(), "Rocky", "DOG", today().minusYears(3), null, null);
        long gone = fx.pet(a.customerId(), "Mochi", "DOG", today().minusYears(5), today().minusDays(3), null);

        ResponseEntity<Map<String, Object>> others = get(a.token(), path(rocky, ""));
        ResponseEntity<Map<String, Object>> unknown = get(a.token(), path(AiReadFixtures.next(), ""));
        ResponseEntity<Map<String, Object>> deceased = get(a.token(), path(gone, ""));
        ResponseEntity<Map<String, Object>> deceasedOfOther = get(b.token(), path(gone, ""));

        assertThat(others.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(others.getBody().get("message")).isEqualTo("Không tìm thấy thú cưng #" + rocky);
        assertThat(deceased.getStatusCode().value()).isEqualTo(400);
        assertThat((String) deceased.getBody().get("message")).contains("BR-KH-05");
        assertThat(deceasedOfOther.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aCustomerTokenIsRequiredAndABadLimitOrGroupIs400() {
        Customer a = customer("Khách A");
        long pet = fx.pet(a.customerId(), "Mochi", "DOG", today().minusWeeks(20), null, null);
        String admin = login(support.staff("ADMIN", null));

        assertThat(get(null, path(pet, "")).getStatusCode().value()).isEqualTo(401);
        assertThat(get(admin, path(pet, "")).getStatusCode().value()).isEqualTo(403);
        for (String bad : List.of("?limit=0", "?limit=13", "?limit=abc", "?group=BOGUS")) {
            ResponseEntity<Map<String, Object>> response = get(a.token(), path(pet, bad));
            assertThat(response.getStatusCode().value()).as(bad).isEqualTo(400);
        }
        assertThat(get(a.token(), path(pet, "?limit=1")).getStatusCode().value()).isEqualTo(200);
        assertThat(get(a.token(), path(pet, "?limit=12")).getStatusCode().value()).isEqualTo(200);
        assertThat(embeddings.calls()).isZero();
        assertThat(llm.calls()).isEmpty();
    }

    // ================================================================ hỗ trợ

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Instant noonVn(LocalDate day) {
        return day.atTime(12, 0).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private Customer customer(String name) {
        long account = support.customerAccount("reco-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        long customerId = support.onlineCustomer(account, name);
        return new Customer(account, customerId, login(account));
    }

    private String login(long accountId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    /** Thú con (dưới 52 tuần) của khách: cùng cohort (DOG, JUNIOR) với Mochi. */
    private long puppyOf(Customer customer) {
        return fx.pet(customer.customerId(), "DOG", today().minusWeeks(10));
    }

    private long groomingService(String name) {
        long id = fx.service("GROOMING", true);
        jdbc.update("UPDATE services SET name = ? WHERE id = ?", name + " " + TAG + " " + id, id);
        fx.branchService(branch, id, true);
        return id;
    }

    private long kennel(String species, String maxKg) {
        long id = fx.service("BOARDING", true);
        fx.kennelType(id, species, maxKg);
        fx.branchService(branch, id, true);
        return id;
    }

    private long stockedProduct(String type, boolean prescription) {
        long id = fx.product(type, prescription, true);
        fx.lot(branch, id, 10, null);
        return id;
    }

    private void profileOfService(long serviceId, String species, Integer minAge, Integer maxAge, String minKg, String maxKg,
            String breed, Integer repeat) {
        profiledServices.add(serviceId);
        jdbc.update("""
                INSERT INTO ai_item_profiles (service_id, species, min_age_weeks, max_age_weeks, min_weight_kg,
                                              max_weight_kg, breed_keywords, repeat_interval_days)
                VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, CASE WHEN ?::text IS NULL THEN NULL
                        ELSE ARRAY[?::text]::varchar[] END, ?)""", serviceId, species, minAge, maxAge, minKg, maxKg, breed,
                breed, repeat);
    }

    private void completedVisit(long customerId, long petId, long serviceId, LocalDate day) {
        long visit = fx.visit(customerId, petId, "COMPLETED", noonVn(day));
        fx.serviceLine(fx.order(customerId, visit, "PAID", noonVn(day)), serviceId);
    }

    private static String path(long petId, String query) {
        return "/api/me/ai/pets/" + petId + "/recommendations" + query;
    }

    private ResponseEntity<Map<String, Object>> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> item) {
        return (List<String>) item.get("reasons");
    }

    private static List<Long> idsOf(List<Map<String, Object>> items, String itemType) {
        return items.stream().filter(i -> itemType.equals(i.get("itemType"))).map(i -> ((Number) i.get("itemId")).longValue())
                .collect(Collectors.toList());
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(i -> ((Number) i.get("itemId")).longValue()).collect(Collectors.toList());
    }
}
