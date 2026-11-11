package com.petcare.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.petcare.support.MutableClock;

/**
 * Hồ sơ gợi ý UC94 qua HTTP, trên Postgres thật (ai-v1 #12 {@code GET /ai/item-profiles}, #13
 * {@code PUT /ai/item-profiles/{itemType}/{itemId}}; BR-AI-17, 18, 20; docs/adr/0133). Chỉ SUPER_MANAGER; PUT ghi đè toàn bộ,
 * rỗng thì xóa dòng; audit cùng transaction; UC92 thấy hồ sơ mới NGAY (bộ nhớ đệm bị xóa sau commit). Tên mục mang
 * {@code TAG} để lọc bằng {@code q}, vì DB dùng chung với IT khác. Era <b>năm 2045</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ItemProfileIT.TestBeans.class})
class ItemProfileIT {

    private static final String TAG = "ItemProfileIT";
    private static final Instant T0 = Instant.parse("2045-03-10T03:00:00Z");
    private static final String BASE = "/api/ai/item-profiles";
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

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AiReadFixtures fx;
    private AppointmentItSupport support;
    private TransactionTemplate tx;
    private long managerId;
    private String manager;
    private long branch;
    private final List<Long> touchedServices = new ArrayList<>();
    private final List<Long> touchedProducts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        fx = new AiReadFixtures(jdbc, transactionManager);
        support = new AppointmentItSupport(jdbc);
        managerId = support.staff("SUPER_MANAGER", null);
        manager = token(managerId);
        branch = fx.branch(TAG + " ", "ACTIVE", false);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_fail_profile_audit ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_fail_profile_audit()");
        touchedServices.forEach(id -> jdbc.update("DELETE FROM ai_item_profiles WHERE service_id = ?", id));
        touchedProducts.forEach(id -> jdbc.update("DELETE FROM ai_item_profiles WHERE product_id = ?", id));
        fx.cleanup();
        support.cleanUp();
    }

    // ================================================================ phân quyền

    @Test
    void onlySuperManagerCanUseBothEndpoints() {
        long service = grooming("Tắm");
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("ADMIN", token(support.staff("ADMIN", null)));
        tokens.put("RECEPTIONIST", token(support.staff("RECEPTIONIST", branch)));
        tokens.put("CUSTOMER", token(support.customerAccount()));

        for (Map.Entry<String, String> caller : tokens.entrySet()) {
            assertThat(call(caller.getValue(), HttpMethod.GET, BASE, null).getStatusCode().value())
                    .as("%s GET", caller.getKey()).isEqualTo(403);
            ResponseEntity<Map<String, Object>> put = call(caller.getValue(), HttpMethod.PUT, BASE + "/SERVICE/" + service,
                    profile("DOG", null, null, null, null, List.of(), null));
            assertThat(put.getStatusCode().value()).as("%s PUT", caller.getKey()).isEqualTo(403);
            assertThat(put.getBody()).containsEntry("errorCode", "ACCESS_DENIED");
        }
        assertThat(call(null, HttpMethod.GET, BASE, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(null, HttpMethod.PUT, BASE + "/SERVICE/" + service,
                profile("DOG", null, null, null, null, List.of(), null)).getStatusCode().value()).isEqualTo(401);
        assertThat(profileRows(service, true)).isZero();
    }

    // ================================================================ GET

    @Test
    void listsOnlyGroomingServicesAndOverTheCounterGoodsAndFiltersByTypeMissingAndName() {
        long bath = grooming("Tắm & vệ sinh");
        long cut = grooming("Cắt tỉa");
        long exam = medical();
        long kennel = fx.service("BOARDING", true);
        jdbc.update("UPDATE services SET name = ? WHERE id = ?", "Chuồng " + TAG, kennel);
        long food = goods("Hạt khô");
        long toy = goods("Đồ chơi");
        long drug = product("DRUG", true, "Thuốc");
        long vaccine = product("VACCINE", false, "Vaccine");
        long off = fx.service("GROOMING", false);
        jdbc.update("UPDATE services SET name = ? WHERE id = ?", "Ngừng " + TAG, off);
        ok(put("SERVICE", bath, profile("DOG", 12, null, "1", "8", List.of("poodle"), 30)));
        ok(put("PRODUCT", toy, profile("CAT", null, null, null, null, List.of(), null)));

        List<Map<String, Object>> all = rows(call(manager, HttpMethod.GET, BASE + "?q=" + TAG + "&size=100", null));

        assertThat(ids(all)).as("dịch vụ trước, sản phẩm sau; không loại chuồng, dịch vụ khám, thuốc, vaccine, mục đã ngừng")
                .containsExactlyInAnyOrder(bath, cut, food, toy);
        assertThat(all.stream().map(r -> r.get("itemType")).toList()).as("dịch vụ trước, sản phẩm sau")
                .containsExactly("SERVICE", "SERVICE", "PRODUCT", "PRODUCT");
        assertThat(byId(all, bath)).containsEntry("hasProfile", true);
        assertThat(byId(all, cut)).containsEntry("hasProfile", false);
        assertThat(byId(all, food)).containsEntry("hasProfile", false);
        assertThat(byId(all, toy)).containsEntry("hasProfile", true);
        assertThat(ids(all)).doesNotContain(exam, kennel, drug, vaccine, off);
        Map<String, Object> bathRow = byId(all, bath);
        assertThat(bathRow).containsEntry("group", "GROOMING");
        assertThat(profileOf(bathRow)).containsEntry("species", "DOG").containsEntry("minAgeWeeks", 12)
                .containsEntry("repeatIntervalDays", 30).containsEntry("breedKeywords", List.of("poodle"));
        assertThat(byId(all, toy)).containsEntry("group", "PRODUCT");

        assertThat(ids(rows(call(manager, HttpMethod.GET, BASE + "?q=" + TAG + "&itemType=PRODUCT", null))))
                .containsExactlyInAnyOrder(food, toy);
        assertThat(ids(rows(call(manager, HttpMethod.GET, BASE + "?q=" + TAG + "&missingOnly=true", null))))
                .containsExactlyInAnyOrder(cut, food);
        assertThat(ids(rows(call(manager, HttpMethod.GET, BASE + "?q=DO%20CHOI%20" + TAG.toUpperCase(), null))))
                .as("tìm theo tên không phân biệt hoa thường và dấu (\"Đồ chơi\" khớp \"DO CHOI\")").containsExactly(toy);
    }

    // ================================================================ PUT

    @Test
    void putCreatesThenReplacesThenClearsAndTheRowFollowsHasProfileAndMissingOnly() {
        long bath = grooming("Tắm");

        Map<String, Object> created = data(put("SERVICE", bath, profile("DOG", 12, null, "1", "8", List.of("poodle"), 30)));
        assertThat(created).containsEntry("hasProfile", true).containsEntry("itemId", (int) bath);
        assertThat(profileRows(bath, true)).isEqualTo(1);
        assertThat(missing(bath)).isFalse();

        Map<String, Object> replaced = data(put("SERVICE", bath, profile(null, null, null, "2", null, List.of(), null)));
        assertThat(((Number) profileOf(replaced).get("minWeightKg")).doubleValue()).isEqualTo(2.0);
        assertThat(profileOf(replaced).get("species")).isNull();
        assertThat(profileOf(replaced).get("minAgeWeeks")).isNull();
        assertThat(profileOf(replaced).get("repeatIntervalDays")).isNull();
        assertThat(profileRows(bath, true)).as("UNIQUE: vẫn một dòng").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT species, min_age_weeks, repeat_interval_days, breed_keywords, "
                + "updated_by FROM ai_item_profiles WHERE service_id = ?", bath);
        assertThat(row.get("species")).isNull();
        assertThat(row.get("min_age_weeks")).isNull();
        assertThat(row.get("repeat_interval_days")).isNull();
        assertThat(row.get("breed_keywords")).isNull();
        assertThat(row.get("updated_by")).isEqualTo(managerId);

        Map<String, Object> cleared = data(put("SERVICE", bath, profile(null, null, null, null, null, List.of(), null)));
        assertThat(cleared).containsEntry("hasProfile", false);
        assertThat(cleared.get("profile")).isNull();
        assertThat(profileRows(bath, true)).isZero();
        assertThat(missing(bath)).as("xóa thì quay về danh sách mục chưa có hồ sơ").isTrue();
        assertThat(data(put("SERVICE", bath, profile(null, null, null, null, null, List.of(), null)))).containsEntry("hasProfile",
                false);
    }

    @Test
    void breedKeywordsAreStoredLowercaseWithoutAccentsAndDeduplicated() {
        long food = goods("Hạt khô");

        Map<String, Object> body = data(put("PRODUCT", food, profile(null, null, null, null, null,
                List.of("  Poodle ", "ĐỐM", "poodle", " "), null)));

        assertThat(profileOf(body)).containsEntry("breedKeywords", List.of("poodle", "dom"));
        assertThat(jdbc.queryForObject("SELECT array_to_string(breed_keywords, ',') FROM ai_item_profiles WHERE product_id = ?",
                String.class, food)).isEqualTo("poodle,dom");
    }

    @Test
    void medicalBoardingDrugAndVaccineItemsCannotHaveAProfileAndUnknownItemsAreNotFound() {
        long exam = medical();
        long kennel = fx.service("BOARDING", true);
        long drug = product("DRUG", true, "Thuốc");
        long otc = product("DRUG", false, "Thuốc không kê đơn");
        long vaccine = product("VACCINE", false, "Vaccine");
        Map<String, Object> body = profile("DOG", null, null, null, null, List.of(), null);

        for (String[] target : new String[][] {{"SERVICE", String.valueOf(exam)}, {"SERVICE", String.valueOf(kennel)},
                {"PRODUCT", String.valueOf(drug)}, {"PRODUCT", String.valueOf(otc)}, {"PRODUCT", String.valueOf(vaccine)}}) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PUT, BASE + "/" + target[0] + "/" + target[1], body);
            assertThat(response.getStatusCode().value()).as(target[0] + " " + target[1]).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
            assertThat((String) response.getBody().get("message")).contains("BR-AI-18");
        }
        assertThat(profileRows(exam, true) + profileRows(kennel, true) + profileRows(drug, false) + profileRows(otc, false)
                + profileRows(vaccine, false)).isZero();
        assertThat(call(manager, HttpMethod.PUT, BASE + "/SERVICE/" + AiReadFixtures.next(), body).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call(manager, HttpMethod.PUT, BASE + "/PRODUCT/" + AiReadFixtures.next(), body).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void invalidBodiesAreRejectedWith400AndNothingIsWritten() {
        long bath = grooming("Tắm");
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(profile("DOG", 20, 10, null, null, List.of(), null));
        bad.add(profile("DOG", null, null, "8", "7.99", List.of(), null));
        bad.add(profile("DOG", null, null, "-1", null, List.of(), null));
        bad.add(profile("DOG", null, null, "1.234", null, List.of(), null));
        bad.add(profile("DOG", null, null, null, null, List.of(), 0));
        bad.add(profile("DOG", -1, null, null, null, List.of(), null));
        bad.add(profile("HAMSTER", null, null, null, null, List.of(), null));
        Map<String, Object> noKeywords = profile("DOG", null, null, null, null, List.of(), null);
        noKeywords.remove("breedKeywords");
        bad.add(noKeywords);

        for (Map<String, Object> body : bad) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PUT, BASE + "/SERVICE/" + bath, body);
            assertThat(response.getStatusCode().value()).as(String.valueOf(body)).isEqualTo(400);
        }
        assertThat(call(manager, HttpMethod.PUT, BASE + "/SERVICE/" + bath, bad.get(0)).getBody())
                .containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat(call(manager, HttpMethod.PUT, BASE + "/OTHER/" + bath, bad.get(0)).getStatusCode().value()).isEqualTo(400);
        assertThat(profileRows(bath, true)).isZero();
        assertThat(call(manager, HttpMethod.PUT, BASE + "/SERVICE/" + bath, profile("DOG", 10, 10, "8", "8", List.of(), 1))
                .getStatusCode().value()).as("min = max hợp lệ").isEqualTo(200);
    }

    // ================================================================ audit

    @Test
    void everyRealWriteIsAuditedWithBeforeAndAfterAndAnUnchangedPutIsNot() {
        long bath = grooming("Tắm");

        ok(put("SERVICE", bath, profile("DOG", 12, null, "1", "8", List.of("poodle"), 30)));
        ok(put("SERVICE", bath, profile("DOG", 12, null, "1", "8", List.of("poodle"), 30)));
        ok(put("SERVICE", bath, profile("CAT", null, null, null, null, List.of(), null)));
        ok(put("SERVICE", bath, profile(null, null, null, null, null, List.of(), null)));

        List<Map<String, Object>> audits = jdbc.queryForList("""
                SELECT action, entity_type, actor_account_id, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'ai_item_profiles' AND actor_account_id = ? ORDER BY id""", managerId);
        assertThat(audits).extracting(a -> a.get("action"))
                .containsExactly("AI_ITEM_PROFILE_SAVED", "AI_ITEM_PROFILE_SAVED", "AI_ITEM_PROFILE_CLEARED");
        assertThat(audits.get(0).get("before_data")).isNull();
        assertThat((String) audits.get(0).get("after_data")).contains("\"species\": \"DOG\"", "\"itemType\": \"SERVICE\"");
        assertThat((String) audits.get(1).get("before_data")).contains("\"species\": \"DOG\"");
        assertThat((String) audits.get(1).get("after_data")).contains("\"species\": \"CAT\"");
        assertThat((String) audits.get(2).get("before_data")).contains("\"species\": \"CAT\"");
        assertThat(audits.get(2).get("after_data")).isNull();
    }

    @Test
    void aFailedAuditRollsTheProfileWriteBack() {
        long bath = grooming("Tắm");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_fail_profile_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: lỗi ghi audit';
                END $$""");
        jdbc.execute("CREATE TRIGGER ai_it_fail_profile_audit BEFORE INSERT ON audit_logs FOR EACH ROW "
                + "WHEN (NEW.action = 'AI_ITEM_PROFILE_SAVED') EXECUTE FUNCTION ai_it_fail_profile_audit()");

        ResponseEntity<Map<String, Object>> response = put("SERVICE", bath, profile("DOG", null, null, null, null, List.of(), null));

        assertThat(response.getStatusCode().is5xxServerError()).as(String.valueOf(response.getBody())).isTrue();
        assertThat(profileRows(bath, true)).isZero();
    }

    // ================================================================ UC92 thấy hồ sơ mới ngay

    @Test
    void recommendationsSeeANewProfileImmediatelyBecauseTheCacheIsClearedAfterCommit() {
        long account = support.customerAccount("profile-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        long customerId = support.onlineCustomer(account, "Chủ Mochi");
        String customer = token(account);
        long pet = fx.pet(customerId, "Mochi", "DOG", LocalDate.now(clock).minusWeeks(20), null, null);
        long bath = grooming("Tắm");
        fx.branchService(branch, bath, true);
        String recommendations = "/api/me/ai/pets/" + pet + "/recommendations?group=GROOMING&limit=12";

        List<Map<String, Object>> before = rows(call(customer, HttpMethod.GET, recommendations, null));
        assertThat(ids(before)).contains(bath);
        assertThat(ids(rows(call(customer, HttpMethod.GET, recommendations, null)))).as("lần hai lấy từ cache").contains(bath);

        ok(put("SERVICE", bath, profile("CAT", null, null, null, null, List.of(), null)));

        assertThat(ids(rows(call(customer, HttpMethod.GET, recommendations, null))))
                .as("hồ sơ chỉ dành cho mèo: Mochi là chó nên mục bị loại ngay, không đợi 10 phút").doesNotContain(bath);

        ok(put("SERVICE", bath, profile(null, null, null, null, null, List.of(), null)));

        assertThat(ids(rows(call(customer, HttpMethod.GET, recommendations, null)))).contains(bath);
    }

    // ================================================================ hỗ trợ

    private long grooming(String name) {
        long id = fx.service("GROOMING", true);
        jdbc.update("UPDATE services SET name = ? WHERE id = ?", name + " " + TAG, id);
        touchedServices.add(id);
        return id;
    }

    private long medical() {
        long id = fx.service("MEDICAL", true);
        jdbc.update("UPDATE services SET name = ? WHERE id = ?", "Khám " + TAG, id);
        touchedServices.add(id);
        return id;
    }

    private long goods(String name) {
        return product("GOODS", false, name);
    }

    private long product(String type, boolean prescription, String name) {
        long id = fx.product(type, prescription, true);
        jdbc.update("UPDATE products SET name = ? WHERE id = ?", name + " " + TAG, id);
        touchedProducts.add(id);
        return id;
    }

    private int profileRows(long itemId, boolean service) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_item_profiles WHERE " + (service ? "service_id" : "product_id")
                + " = ?", Integer.class, itemId);
    }

    /** Có nằm trong danh sách "chưa có hồ sơ" (missingOnly) hay không. */
    private boolean missing(long serviceId) {
        return ids(rows(call(manager, HttpMethod.GET, BASE + "?q=" + TAG + "&missingOnly=true&size=100", null)))
                .contains(serviceId);
    }

    private static Map<String, Object> profile(String species, Integer minAge, Integer maxAge, String minKg, String maxKg,
            List<String> breeds, Integer repeat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("species", species);
        body.put("minAgeWeeks", minAge);
        body.put("maxAgeWeeks", maxAge);
        body.put("minWeightKg", minKg == null ? null : new java.math.BigDecimal(minKg));
        body.put("maxWeightKg", maxKg == null ? null : new java.math.BigDecimal(maxKg));
        body.put("breedKeywords", breeds);
        body.put("repeatIntervalDays", repeat);
        return body;
    }

    private ResponseEntity<Map<String, Object>> put(String itemType, long itemId, Map<String, Object> body) {
        return call(manager, HttpMethod.PUT, BASE + "/" + itemType + "/" + itemId, body);
    }

    private static ResponseEntity<Map<String, Object>> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        return response;
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        ok(response);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ResponseEntity<Map<String, Object>> response) {
        ok(response);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Object content = data.get("content");
        return content == null ? (List<Map<String, Object>>) data.get("items") : (List<Map<String, Object>>) content;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profileOf(Map<String, Object> row) {
        return (Map<String, Object>) row.get("profile");
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> ((Number) r.get("itemId")).longValue()).collect(Collectors.toList());
    }

    private static Map<String, Object> byId(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(r -> ((Number) r.get("itemId")).longValue() == id).findFirst().orElseThrow();
    }
}
