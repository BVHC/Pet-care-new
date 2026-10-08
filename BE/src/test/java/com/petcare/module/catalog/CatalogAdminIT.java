package com.petcare.module.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi;

/**
 * UC28–UC31 (catalog-v1 #1–18) trên Postgres 17 thật qua HTTP: RBAC (đọc mọi nhân viên, ghi SUPER_MANAGER),
 * BR-SP-01…07, audit đổi giá. {@code StockQueryApi} và {@code VaccinationQueryApi} chưa có implementation nên được
 * thay bằng mock. Bảng catalog không dọn giữa các test: mỗi test dùng tên duy nhất và lọc theo khóa của chính nó.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CatalogAdminIT {

    @MockitoBean
    private StockQueryApi stock;

    @MockitoBean
    private VaccinationQueryApi vaccinations;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CatalogQueryApi catalog;

    private String superManager;
    private String vet;
    private String customer;

    @BeforeEach
    void setUp() {
        reset(stock, vaccinations);
        long branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "CatalogIT chi nhánh " + UUID.randomUUID());
        superManager = token(staff("SUPER_MANAGER", null));
        vet = token(staff("VET", branchId));
        customer = token(jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "catalog-it-c-" + UUID.randomUUID() + "@petcare.test"));
    }

    // ------------------------------------------------------------------ RBAC

    @Test
    void staffReadsCustomerAndAnonymousCannot() {
        assertThat(call(HttpMethod.GET, "/api/product-categories", vet, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/services", vet, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/vaccine-types", vet, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/vaccination-protocols", vet, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/products", vet, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(call(HttpMethod.GET, "/api/product-categories", customer, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, "/api/products", null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void onlySuperManagerWrites() {
        assertError(call(HttpMethod.POST, "/api/product-categories", vet, Map.of("name", unique("Danh mục"))),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, "/api/vaccine-types", vet,
                Map.of("name", unique("Dại"), "species", "DOG")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.DELETE, "/api/vaccine-types/1", vet, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
    }

    // ------------------------------------------------------------------ UC28 danh mục

    @Test
    void categoryLifecycleAndResponseShape() {
        String name = unique("Thức ăn");
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/product-categories", superManager,
                Map.of("name", name));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> category = data(created);
        assertThat(category).containsEntry("name", name).containsEntry("isActive", true).containsKey("categoryId")
                .doesNotContainKey("active").doesNotContainKey("id");
        long id = ((Number) category.get("categoryId")).longValue();

        assertError(call(HttpMethod.POST, "/api/product-categories", superManager, Map.of("name", name)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, "/api/product-categories", superManager, Map.of("name", " ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        ResponseEntity<Map<String, Object>> hidden = call(HttpMethod.PATCH, "/api/product-categories/" + id,
                superManager, Map.of("isActive", false));
        assertThat(data(hidden)).containsEntry("isActive", false).containsEntry("name", name);
        assertError(call(HttpMethod.PATCH, "/api/product-categories/999999999", superManager, Map.of("name", "x")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ UC29 sản phẩm

    @Test
    void productCrudFiltersAndRetailOnly() {
        long categoryId = category();
        String q = unique("Pate");
        long goods = product(categoryId, q + "-GOODS", "GOODS", false, false, null);
        long drug = product(categoryId, q + "-RX", "DRUG", true, true, null);

        ResponseEntity<Map<String, Object>> product = call(HttpMethod.GET, "/api/products/" + goods, vet, null);
        assertThat(data(product)).containsEntry("productType", "GOODS").containsEntry("isPrescription", false)
                .containsEntry("isActive", true).containsEntry("tracksExpiry", false).containsKey("productId")
                .doesNotContainKey("prescription").doesNotContainKey("active");

        Map<String, Object> all = data(call(HttpMethod.GET,
                "/api/products?categoryId=" + categoryId + "&q=" + q.toLowerCase() + "&size=10", vet, null));
        assertThat(all).containsEntry("totalElements", 2).containsEntry("page", 0);
        assertThat(contentIds(all)).containsExactlyInAnyOrder(goods, drug);

        Map<String, Object> retail = data(call(HttpMethod.GET,
                "/api/products?categoryId=" + categoryId + "&retailOnly=true", vet, null));
        assertThat(contentIds(retail)).containsExactly(goods);

        Map<String, Object> drugs = data(call(HttpMethod.GET,
                "/api/products?categoryId=" + categoryId + "&productType=DRUG", vet, null));
        assertThat(contentIds(drugs)).containsExactly(drug);

        call(HttpMethod.PATCH, "/api/products/" + goods, superManager, Map.of("isActive", false));
        Map<String, Object> inactive = data(call(HttpMethod.GET,
                "/api/products?categoryId=" + categoryId + "&isActive=false", vet, null));
        assertThat(contentIds(inactive)).containsExactly(goods);
    }

    @Test
    void productRulesAreEnforced() {
        long categoryId = category();
        long vaccineTypeId = vaccineType("DOG");

        assertRule(createProduct(categoryId, unique("SKU"), "GOODS", true, true, null), "BR-SP-01");
        assertRule(createProduct(categoryId, unique("SKU"), "DRUG", true, false, null), "BR-SP-05");
        assertRule(createProduct(categoryId, unique("SKU"), "VACCINE", false, false, vaccineTypeId), "BR-SP-05");
        assertRule(createProduct(categoryId, unique("SKU"), "VACCINE", false, true, null), "BR-SP-07");
        assertRule(createProduct(categoryId, unique("SKU"), "GOODS", false, false, vaccineTypeId), "BR-SP-07");

        String sku = unique("SKU");
        assertThat(createProduct(categoryId, sku, "VACCINE", false, true, vaccineTypeId).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertError(createProduct(categoryId, sku, "GOODS", false, false, null), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(createProduct(999_999_999L, unique("SKU"), "GOODS", false, false, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void cannotTurnOffExpiryWhileStockExists_BR_SP_05() {
        long productId = product(category(), unique("SKU"), "GOODS", false, true, null);
        when(stock.hasStockAnywhere(productId)).thenReturn(true);

        assertRule(call(HttpMethod.PATCH, "/api/products/" + productId, superManager, Map.of("tracksExpiry", false)),
                "BR-SP-05");
        assertThat(jdbc.queryForObject("SELECT tracks_expiry FROM products WHERE id = ?", Boolean.class, productId))
                .isTrue();

        when(stock.hasStockAnywhere(productId)).thenReturn(false);
        assertThat(data(call(HttpMethod.PATCH, "/api/products/" + productId, superManager,
                Map.of("tracksExpiry", false)))).containsEntry("tracksExpiry", false);
    }

    @Test
    void priceChangeIsAuditedOnce() {
        long productId = product(category(), unique("SKU"), "GOODS", false, false, null);

        call(HttpMethod.PATCH, "/api/products/" + productId, superManager, Map.of("name", "Tên mới"));
        assertThat(priceAudits("products", productId)).isZero();

        ResponseEntity<Map<String, Object>> changed = call(HttpMethod.PATCH, "/api/products/" + productId,
                superManager, Map.of("price", 250_000));
        assertThat(data(changed)).containsEntry("price", 250_000);
        assertThat(priceAudits("products", productId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE action = 'PRICE_CHANGED' AND entity_type = 'products' AND entity_id = ?
                """, productId);
        assertThat((String) row.get("before_data")).contains("100000");
        assertThat((String) row.get("after_data")).contains("250000");
    }

    // ------------------------------------------------------------------ UC30 dịch vụ

    @Test
    void serviceRulesAndKennelType() {
        assertRule(createService(unique("Khám"), "MEDICAL", null, 100_000, null), "BR-SP-06");
        assertRule(createService(unique("Spa"), "GROOMING", "EXAM", 100_000, null), "BR-SP-06");
        assertRule(createService(unique("Chuồng"), "BOARDING", null, 0, Map.of("species", "DOG", "maxWeightKg", 10)),
                "BR-SP-04");
        assertRule(createService(unique("Chuồng"), "BOARDING", null, 100_000, null), "BR-SP-04");
        assertRule(createService(unique("Chuồng"), "BOARDING", null, 100_000, Map.of("maxWeightKg", 10)),
                "BR-SP-04");
        assertRule(createService(unique("Chuồng"), "BOARDING", null, 100_000,
                Map.of("species", "DOG", "maxWeightKg", 0)), "BR-SP-04");
        assertRule(createService(unique("Spa"), "GROOMING", null, 100_000, Map.of("species", "DOG", "maxWeightKg", 5)),
                "BR-SP-04");

        ResponseEntity<Map<String, Object>> medical = createService(unique("Tiêm"), "MEDICAL", "VACCINE", 150_000,
                null);
        assertThat(medical.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(medical)).containsEntry("medicalType", "VACCINE").containsEntry("priceIsFrom", true)
                .containsEntry("isActive", true).doesNotContainKey("active");

        String kennelName = unique("Chuồng chó nhỏ");
        ResponseEntity<Map<String, Object>> kennel = createService(kennelName, "BOARDING", null, 120_000,
                Map.of("species", "DOG", "maxWeightKg", 12.5));
        assertThat(kennel.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long kennelId = ((Number) data(kennel).get("serviceId")).longValue();

        Map<String, Object> fetched = data(call(HttpMethod.GET, "/api/services/" + kennelId, vet, null));
        assertThat(fetched).containsEntry("group", "BOARDING");
        assertThat(fetched.get("kennelType")).isInstanceOfSatisfying(Map.class,
                kennelType -> assertThat(kennelType.get("species")).isEqualTo("DOG"));

        call(HttpMethod.PATCH, "/api/services/" + kennelId, superManager,
                Map.of("price", 130_000, "kennelType", Map.of("maxWeightKg", 20)));
        assertThat(priceAudits("services", kennelId)).isEqualTo(1);

        CatalogQueryApi.KennelTypeInfo info = catalog.findKennelType(kennelId).orElseThrow();
        assertThat(info.nightlyPrice()).isEqualTo(130_000);
        assertThat(info.species()).isEqualTo(Species.DOG);
        assertThat(info.maxWeightKg()).isEqualByComparingTo("20");

        assertRule(call(HttpMethod.PATCH, "/api/services/" + kennelId, superManager, Map.of("price", 0)), "BR-SP-04");
        List<Long> boardingIds = idsOf(dataList(call(HttpMethod.GET, "/api/services?group=BOARDING&isActive=true",
                vet, null)), "serviceId");
        assertThat(boardingIds).contains(kennelId);
    }

    // ------------------------------------------------------------------ UC31 loại vaccine & phác đồ

    @Test
    void vaccineTypeDeleteRespectsUsage_BR_SP_07() {
        long unused = vaccineType("DOG");
        assertThat(call(HttpMethod.DELETE, "/api/vaccine-types/" + unused, superManager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccine_types WHERE id = ?", Integer.class, unused))
                .isZero();
        assertError(call(HttpMethod.DELETE, "/api/vaccine-types/" + unused, superManager, null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        long withProtocol = vaccineType("DOG");
        createProtocol(withProtocol, "DOG", 1, 21, 6, true);
        assertRule(call(HttpMethod.DELETE, "/api/vaccine-types/" + withProtocol, superManager, null), "BR-SP-07");

        long withProduct = vaccineType("CAT");
        createProduct(category(), unique("SKU"), "VACCINE", false, true, withProduct);
        assertRule(call(HttpMethod.DELETE, "/api/vaccine-types/" + withProduct, superManager, null), "BR-SP-07");

        long withVaccination = vaccineType("CAT");
        when(vaccinations.existsByVaccineType(withVaccination)).thenReturn(true);
        assertRule(call(HttpMethod.DELETE, "/api/vaccine-types/" + withVaccination, superManager, null), "BR-SP-07");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaccine_types WHERE id IN (?, ?, ?)", Integer.class,
                withProtocol, withProduct, withVaccination)).isEqualTo(3);
    }

    @Test
    void vaccineTypeDuplicateAndDeactivate() {
        String name = unique("5 bệnh");
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/vaccine-types", superManager,
                Map.of("name", name, "species", "DOG"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(call(HttpMethod.POST, "/api/vaccine-types", superManager, Map.of("name", name, "species", "DOG")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(call(HttpMethod.POST, "/api/vaccine-types", superManager,
                Map.of("name", name, "species", "CAT")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(call(HttpMethod.POST, "/api/vaccine-types", superManager, Map.of("name", name)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        long id = ((Number) data(created).get("vaccineTypeId")).longValue();
        assertThat(data(call(HttpMethod.PATCH, "/api/vaccine-types/" + id, superManager,
                Map.of("isActive", false)))).containsEntry("isActive", false).containsEntry("species", "DOG");
    }

    @Test
    void protocolRulesAndQueryApi() {
        long rabies = vaccineType("DOG");
        long fiveInOne = vaccineType("DOG");

        ResponseEntity<Map<String, Object>> first = createProtocol(rabies, "DOG", 1, 21, 6, true);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(first)).containsEntry("doseNumber", 1).containsEntry("requiredForBoarding", true)
                .containsEntry("isActive", true).containsKey("protocolId");
        long firstId = ((Number) data(first).get("protocolId")).longValue();
        assertThat(createProtocol(rabies, "DOG", 2, 365, 9, true).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createProtocol(fiveInOne, "DOG", 1, 21, 6, false).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(createProtocol(rabies, "DOG", 1, 30, 6, true), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertRule(createProtocol(rabies, "DOG", 3, 0, 6, true), "BR-SP-02");
        assertRule(createProtocol(rabies, "DOG", 3, -1, 6, true), "BR-SP-02");
        assertRule(createProtocol(rabies, "CAT", 3, 30, 6, true), "BR-SP-02");
        assertError(call(HttpMethod.POST, "/api/vaccination-protocols", superManager,
                Map.of("species", "DOG", "vaccineTypeId", rabies, "doseNumber", 3)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(createProtocol(999_999_999L, "DOG", 1, 30, 6, true), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");

        assertRule(call(HttpMethod.PATCH, "/api/vaccination-protocols/" + firstId, superManager,
                Map.of("intervalDays", 0)), "BR-SP-02");
        Map<String, Object> updated = data(call(HttpMethod.PATCH, "/api/vaccination-protocols/" + firstId,
                superManager, Map.of("intervalDays", 28, "requiredForBoarding", false)));
        assertThat(updated).containsEntry("intervalDays", 28).containsEntry("requiredForBoarding", false)
                .containsEntry("minAgeWeeks", 6);

        List<CatalogQueryApi.ProtocolDose> doses = catalog.findProtocol(Species.DOG, rabies);
        assertThat(doses).extracting(CatalogQueryApi.ProtocolDose::doseNumber).containsExactly(1, 2);
        assertThat(catalog.findBoardingRequiredVaccineTypeIds(Species.DOG)).contains(rabies)
                .doesNotContain(fiveInOne);

        call(HttpMethod.PATCH, "/api/vaccination-protocols/" + doses.get(1).protocolId(), superManager,
                Map.of("isActive", false));
        assertThat(catalog.findProtocol(Species.DOG, rabies)).extracting(CatalogQueryApi.ProtocolDose::doseNumber)
                .containsExactly(1);
        assertThat(catalog.findProtocolDose(doses.get(1).protocolId())).get()
                .extracting(CatalogQueryApi.ProtocolDose::active).isEqualTo(false);

        List<Long> listed = idsOf(dataList(call(HttpMethod.GET,
                "/api/vaccination-protocols?species=DOG&vaccineTypeId=" + rabies, vet, null)), "protocolId");
        assertThat(listed).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private long category() {
        return ((Number) data(call(HttpMethod.POST, "/api/product-categories", superManager,
                Map.of("name", unique("Danh mục")))).get("categoryId")).longValue();
    }

    private long vaccineType(String species) {
        return ((Number) data(call(HttpMethod.POST, "/api/vaccine-types", superManager,
                Map.of("name", unique("Vaccine"), "species", species))).get("vaccineTypeId")).longValue();
    }

    private long product(long categoryId, String sku, String type, boolean prescription, boolean tracksExpiry,
            Long vaccineTypeId) {
        ResponseEntity<Map<String, Object>> response = createProduct(categoryId, sku, type, prescription,
                tracksExpiry, vaccineTypeId);
        assertThat(response.getStatusCode()).as("create %s: %s", sku, response.getBody()).isEqualTo(HttpStatus.CREATED);
        return ((Number) data(response).get("productId")).longValue();
    }

    private ResponseEntity<Map<String, Object>> createProduct(long categoryId, String sku, String type,
            boolean prescription, boolean tracksExpiry, Long vaccineTypeId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("categoryId", categoryId);
        body.put("sku", sku);
        body.put("name", sku);
        body.put("productType", type);
        body.put("isPrescription", prescription);
        body.put("tracksExpiry", tracksExpiry);
        body.put("vaccineTypeId", vaccineTypeId);
        body.put("unit", "hộp");
        body.put("price", 100_000);
        return call(HttpMethod.POST, "/api/products", superManager, body);
    }

    private ResponseEntity<Map<String, Object>> createService(String name, String group, String medicalType,
            long price, Map<String, Object> kennelType) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("group", group);
        body.put("medicalType", medicalType);
        body.put("price", price);
        body.put("kennelType", kennelType);
        return call(HttpMethod.POST, "/api/services", superManager, body);
    }

    private ResponseEntity<Map<String, Object>> createProtocol(long vaccineTypeId, String species, int dose,
            int intervalDays, int minAgeWeeks, boolean requiredForBoarding) {
        return call(HttpMethod.POST, "/api/vaccination-protocols", superManager,
                Map.of("species", species, "vaccineTypeId", vaccineTypeId, "doseNumber", dose,
                        "intervalDays", intervalDays, "minAgeWeeks", minAgeWeeks,
                        "requiredForBoarding", requiredForBoarding));
    }

    private int priceAudits(String entityType, long entityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE action = 'PRICE_CHANGED' AND entity_type = ? AND entity_id = ?
                """, Integer.class, entityType, entityId);
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "catalog-it-" + UUID.randomUUID() + "@petcare.test", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return id;
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "CatalogAdminIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).as("body of %s", response).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).as("body of %s", response).isNotNull();
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> contentIds(Map<String, Object> page) {
        return idsOf((List<Map<String, Object>>) page.get("content"), "productId");
    }

    private static List<Long> idsOf(List<Map<String, Object>> items, String key) {
        return items.stream().map(m -> ((Number) m.get(key)).longValue()).toList();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsKeys("message", "timestamp", "traceId");
    }
}
