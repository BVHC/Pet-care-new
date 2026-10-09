package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.service.SessionService;

/**
 * UC33, UC42 (branch-v1 #13–19) trên Postgres 17 thật qua HTTP: bật / tắt dịch vụ (BR-LH-01, BR-SP-04), quota mặc định
 * và quota riêng của khung (BR-LH-03, BR-LH-02), phạm vi chi nhánh, và việc {@code BranchQueryApi} đọc đúng các bảng đó.
 * Không mock module nào: {@code CatalogQueryApi} đã có implementation thật. Giờ mở cửa chèn bằng SQL thô để bắt lỗi
 * lệch giờ cột {@code TIME}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BranchSettingsIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BranchQueryApi branchQuery;

    private long branchA;
    private long branchB;
    private String superManager;
    private String managerA;
    private String managerB;
    private String receptionistA;
    private String vetA;
    private String customer;

    @BeforeEach
    void setUp() {
        branchA = branchRow();
        branchB = branchRow();
        superManager = token(staffAccount("SUPER_MANAGER", null));
        managerA = token(staffAccount("BRANCH_MANAGER", branchA));
        managerB = token(staffAccount("BRANCH_MANAGER", branchB));
        receptionistA = token(staffAccount("RECEPTIONIST", branchA));
        vetA = token(staffAccount("VET", branchA));
        customer = token(jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "settings-it-c-" + UUID.randomUUID() + "@petcare.test"));
    }

    // ------------------------------------------------------------------ UC33 dịch vụ tại chi nhánh

    @Test
    void servicesAreReadByBranchStaffAndWrittenByTheBranchManagerOnly() {
        long spa = service("GROOMING", null, 200_000, true);
        String list = "/api/branches/" + branchA + "/services";
        String put = list + "/" + spa;
        Map<String, Object> on = Map.of("enabled", true);

        assertThat(call(HttpMethod.GET, list, vetA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, list, superManager, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(call(HttpMethod.GET, list, customer, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, list, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.GET, list, managerB, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        assertError(call(HttpMethod.PUT, put, managerB, on), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.PUT, put, vetA, on), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.PUT, put, receptionistA, on), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.PUT, put, superManager, on), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(call(HttpMethod.PUT, put, managerA, on).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void listShowsActiveServicesWithTheirFlagAndHidesStoppedOnes() {
        long exam = service("MEDICAL", "EXAM", 120_000, true);
        long spa = service("GROOMING", null, 200_000, true);
        long kennel = service("BOARDING", null, 150_000, true);
        long stopped = service("GROOMING", null, 90_000, false);
        call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/" + exam, managerA, Map.of("enabled", true));
        call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/" + spa, managerA, Map.of("enabled", true));
        call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/" + spa, managerA, Map.of("enabled", false));

        List<Map<String, Object>> items = dataList(call(HttpMethod.GET, "/api/branches/" + branchA + "/services",
                vetA, null));

        Map<Long, Map<String, Object>> byId = new HashMap<>();
        items.forEach(m -> byId.put(((Number) m.get("serviceId")).longValue(), m));
        assertThat(byId).containsKeys(exam, spa, kennel).doesNotContainKey(stopped);
        assertThat(byId.get(exam)).containsEntry("enabled", true).containsEntry("group", "MEDICAL")
                .containsKey("name");
        assertThat(byId.get(spa)).containsEntry("enabled", false);          // đã tắt
        assertThat(byId.get(kennel)).containsEntry("enabled", false).containsEntry("group", "BOARDING");  // chưa có dòng
    }

    @Test
    void enablingAndDisablingIsVisibleThroughBranchQueryApi_BR_LH_01_BR_SP_04() {
        long kennel = service("BOARDING", null, 150_000, true);
        String path = "/api/branches/" + branchA + "/services/" + kennel;
        assertThat(branchQuery.isServiceEnabled(branchA, kennel)).isFalse();

        ResponseEntity<Map<String, Object>> on = call(HttpMethod.PUT, path, managerA, Map.of("enabled", true));
        assertThat(data(on)).containsEntry("serviceId", (int) kennel).containsEntry("enabled", true)
                .containsEntry("group", "BOARDING");
        assertThat(branchQuery.isServiceEnabled(branchA, kennel)).isTrue();
        assertThat(branchQuery.findActiveBranchIdsEnablingService(kennel)).containsExactly(branchA);
        assertThat(branchQuery.isServiceEnabled(branchB, kennel)).isFalse();      // chi nhánh khác không bị ảnh hưởng

        call(HttpMethod.PUT, path, managerA, Map.of("enabled", false));
        assertThat(branchQuery.isServiceEnabled(branchA, kennel)).isFalse();
        assertThat(branchQuery.findActiveBranchIdsEnablingService(kennel)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_services WHERE branch_id = ? AND service_id = ?",
                Integer.class, branchA, kennel)).isEqualTo(1);                    // cập nhật dòng cũ, không thêm dòng
    }

    @Test
    void onlyActiveBranchesAreListedAsEnablingAService() {
        long spa = service("GROOMING", null, 200_000, true);
        long draft = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'a', '0280000000', 10.7, 106.6, 'DRAFT') RETURNING id
                """, Long.class, unique("Draft"));
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)", draft, spa);
        call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/" + spa, managerA, Map.of("enabled", true));

        assertThat(branchQuery.findActiveBranchIdsEnablingService(spa)).containsExactly(branchA);
    }

    @Test
    void cannotEnableAStoppedServiceButCanDisableIt_BR_LH_01() {
        long stopped = service("GROOMING", null, 90_000, false);
        String path = "/api/branches/" + branchA + "/services/" + stopped;
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)",
                branchA, stopped);

        assertRule(call(HttpMethod.PUT, path, managerA, Map.of("enabled", true)), "BR-LH-01");
        assertThat(call(HttpMethod.PUT, path, managerA, Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(branchQuery.isServiceEnabled(branchA, stopped)).isFalse();
    }

    @Test
    void unknownServiceBranchAndBadBody() {
        long spa = service("GROOMING", null, 200_000, true);

        assertError(call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/999999999", managerA,
                Map.of("enabled", true)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.PUT, "/api/branches/" + branchA + "/services/" + spa, managerA, Map.of()),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, "/api/branches/999999999/services", superManager, null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ UC42 quota mặc định

    @Test
    void quotaIsReadByManagerAndReceptionistOnly() {
        String path = "/api/branches/" + branchA + "/quota-defaults";

        assertThat(call(HttpMethod.GET, path, managerA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, path, receptionistA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(call(HttpMethod.GET, path, vetA, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, path, superManager, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, path, managerB, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.PUT, path + "/MEDICAL", receptionistA, Map.of("defaultQuota", 2)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.PUT, path + "/MEDICAL", managerB, Map.of("defaultQuota", 2)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void defaultQuotaLifecycle_BR_LH_03() {
        String path = "/api/branches/" + branchA + "/quota-defaults";
        LocalDate day = LocalDate.now(ZONE).plusDays(5);

        List<Map<String, Object>> initial = dataList(call(HttpMethod.GET, path, managerA, null));
        assertThat(initial).extracting(m -> m.get("serviceGroup")).containsExactly("MEDICAL", "GROOMING");
        assertThat(initial).allSatisfy(m -> assertThat(m).containsEntry("defaultQuota", null));
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, LocalTime.of(9, 0))).isEqualTo(1);

        ResponseEntity<Map<String, Object>> set = call(HttpMethod.PUT, path + "/MEDICAL", managerA,
                Map.of("defaultQuota", 3));
        assertThat(data(set)).containsEntry("serviceGroup", "MEDICAL").containsEntry("defaultQuota", 3);
        call(HttpMethod.PUT, path + "/GROOMING", managerA, Map.of("defaultQuota", 0));
        call(HttpMethod.PUT, path + "/MEDICAL", managerA, Map.of("defaultQuota", 2));     // ghi đè, giảm không bị chặn

        List<Map<String, Object>> after = dataList(call(HttpMethod.GET, path, receptionistA, null));
        assertThat(after.get(0)).containsEntry("defaultQuota", 2);
        assertThat(after.get(1)).containsEntry("defaultQuota", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_quota_defaults WHERE branch_id = ?",
                Integer.class, branchA)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM branch_quota_defaults WHERE branch_id = ? "
                + "AND service_group = 'MEDICAL'", Long.class, branchA)).isNotNull();
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, LocalTime.of(9, 0))).isEqualTo(2);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.GROOMING, day, LocalTime.of(9, 0))).isZero();
    }

    @Test
    void invalidDefaultQuotaIsRejected_BR_LH_03() {
        String path = "/api/branches/" + branchA + "/quota-defaults";

        assertRule(call(HttpMethod.PUT, path + "/BOARDING", managerA, Map.of("defaultQuota", 2)), "BR-LH-03");
        assertRule(call(HttpMethod.PUT, path + "/MEDICAL", managerA, Map.of("defaultQuota", -1)), "BR-LH-03");
        assertRule(call(HttpMethod.PUT, path + "/MEDICAL", managerA, Map.of("defaultQuota", 32_768)), "BR-LH-03");
        assertError(call(HttpMethod.PUT, path + "/MEDICAL", managerA, Map.of()), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(call(HttpMethod.PUT, path + "/FOO", managerA, Map.of("defaultQuota", 1)).getStatusCode()
                .is4xxClientError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM branch_quota_defaults WHERE branch_id = ?",
                Integer.class, branchA)).isZero();
        assertThat(call(HttpMethod.PUT, path + "/MEDICAL", managerA, Map.of("defaultQuota", 32_767)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ UC42 quota riêng của khung

    @Test
    void slotQuotaLifecycle_BR_LH_03() {
        LocalDate monday = nextMonday();
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30));
        String path = "/api/branches/" + branchA + "/slot-quotas";
        LocalTime nine = LocalTime.of(9, 0);
        call(HttpMethod.PUT, "/api/branches/" + branchA + "/quota-defaults/MEDICAL", managerA,
                Map.of("defaultQuota", 4));

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.PUT, path, managerA,
                slotBody("MEDICAL", monday, "09:00", 0));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(created)).containsEntry("serviceGroup", "MEDICAL").containsEntry("slotDate", monday.toString())
                .containsEntry("slotStart", "09:00").containsEntry("quota", 0).containsKey("slotQuotaId");
        long id = ((Number) data(created).get("slotQuotaId")).longValue();
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, monday, nine)).isZero();          // khóa khung
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, monday, LocalTime.of(9, 30))).isEqualTo(4);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.GROOMING, monday, nine)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> overwritten = call(HttpMethod.PUT, path, managerA,
                slotBody("MEDICAL", monday, "09:00", 5));
        assertThat(((Number) data(overwritten).get("slotQuotaId")).longValue()).isEqualTo(id);          // cùng một dòng
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_quotas WHERE branch_id = ?", Integer.class,
                branchA)).isEqualTo(1);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, monday, nine)).isEqualTo(5);
        call(HttpMethod.PUT, path, managerA, slotBody("GROOMING", monday, "14:00", 2));

        String range = "?from=" + monday + "&to=" + monday.plusDays(1);
        assertThat(dataList(call(HttpMethod.GET, path + range, receptionistA, null))).hasSize(2);
        List<Map<String, Object>> onlyGrooming = dataList(call(HttpMethod.GET,
                path + range + "&serviceGroup=GROOMING", managerA, null));
        assertThat(onlyGrooming).hasSize(1);
        assertThat(onlyGrooming.get(0)).containsEntry("slotStart", "14:00").containsEntry("serviceGroup", "GROOMING");
        assertThat(dataList(call(HttpMethod.GET, path + "?from=" + monday.plusDays(1) + "&to=" + monday.plusDays(2),
                managerA, null))).isEmpty();

        assertThat(call(HttpMethod.DELETE, path + "/" + id, managerA, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, monday, nine)).isEqualTo(4);     // về mặc định
        assertError(call(HttpMethod.DELETE, path + "/" + id, managerA, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void slotMustBeOneTheAppointmentModuleWouldGenerate_BR_LH_02() {
        LocalDate monday = nextMonday();
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30));
        String path = "/api/branches/" + branchA + "/slot-quotas";
        jdbc.update("INSERT INTO holidays (branch_id, holiday_date, created_by) VALUES (?, ?, ?)",
                branchA, monday.plusDays(1), staffAccount("BRANCH_MANAGER", branchA));

        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "07:30", 1)), "BR-LH-02");   // trước giờ mở
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "09:10", 1)), "BR-LH-02");   // lệch bước 30 phút
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "11:45", 1)), "BR-LH-02");   // vắt qua 12:00
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "13:00", 1)), "BR-LH-02");   // giờ nghỉ giữa ca
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "18:45", 1)), "BR-LH-02");   // sau 19:00
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday.plusDays(1), "09:00", 1)),
                "BR-LH-02");                                                                                        // ngày nghỉ
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday.plusDays(6), "09:00", 1)),
                "BR-LH-02");                                                                                        // Chủ nhật nghỉ cố định
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_quotas WHERE branch_id = ?", Integer.class,
                branchA)).isZero();
        assertThat(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "11:30", 1)).getStatusCode())
                .isEqualTo(HttpStatus.OK);                                                                          // khung cuối buổi sáng
        assertThat(call(HttpMethod.PUT, path, managerA, slotBody("GROOMING", monday, "18:30", 1)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidSlotQuotaRequests() {
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30));
        LocalDate monday = nextMonday();
        String path = "/api/branches/" + branchA + "/slot-quotas";

        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("BOARDING", monday, "09:00", 1)), "BR-LH-03");
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "09:00", -1)), "BR-LH-03");
        assertRule(call(HttpMethod.PUT, path, managerA, slotBody("MEDICAL", monday, "09:00", 40_000)), "BR-LH-03");
        Map<String, Object> missing = slotBody("MEDICAL", monday, "09:00", 1);
        missing.remove("slotStart");
        assertError(call(HttpMethod.PUT, path, managerA, missing), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(call(HttpMethod.GET, path + "?from=" + monday, managerA, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);                                  // thiếu "to"
        assertRule(call(HttpMethod.GET, path + "?from=" + monday.plusDays(1) + "&to=" + monday, managerA, null),
                "BR-LH-03");
        assertError(call(HttpMethod.PUT, path, managerB, slotBody("MEDICAL", monday, "09:00", 1)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.PUT, path, receptionistA, slotBody("MEDICAL", monday, "09:00", 1)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void aManagerCannotDeleteAnotherBranchsSlotQuota() {
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30));
        long id = ((Number) data(call(HttpMethod.PUT, "/api/branches/" + branchA + "/slot-quotas", managerA,
                slotBody("MEDICAL", nextMonday(), "09:00", 2))).get("slotQuotaId")).longValue();

        assertError(call(HttpMethod.DELETE, "/api/branches/" + branchA + "/slot-quotas/" + id, managerB, null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        // Id của chi nhánh A nhưng gọi qua chi nhánh B (B tự là của mình): không thấy.
        assertError(call(HttpMethod.DELETE, "/api/branches/" + branchB + "/slot-quotas/" + id, managerB, null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_quotas WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void twoConcurrentWritesToTheSameSlotLeaveOneRow() throws Exception {
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30));
        LocalDate monday = nextMonday();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (int quota = 1; quota <= 2; quota++) {
                int value = quota;
                results.add(pool.submit(() -> {
                    go.await();
                    return HttpStatus.valueOf(call(HttpMethod.PUT, "/api/branches/" + branchA + "/slot-quotas",
                            managerA, slotBody("MEDICAL", monday, "09:00", value)).getStatusCode().value());
                }));
            }
            go.countDown();
            for (Future<HttpStatus> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot_quotas WHERE branch_id = ?", Integer.class,
                branchA)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private long branchRow() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, unique("SettingsIT"));
    }

    /** Dịch vụ trong danh mục; {@code BOARDING} là loại chuồng (giá phải lớn hơn 0, BR-SP-04). */
    private long service(String group, String medicalType, long price, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price, is_active)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, unique("Svc"), group, medicalType, price, active);
    }

    /** Giờ mở cửa Thứ Hai–Thứ Bảy 08:00–12:00 và 14:00–19:00, Chủ nhật nghỉ, bằng SQL thô. */
    private void hoursRows(long branchId, LocalDate effectiveFrom) {
        for (int day = 1; day <= 7; day++) {
            if (day == 7) {
                jdbc.update("INSERT INTO opening_hours (branch_id, effective_from, day_of_week) VALUES (?, ?, ?)",
                        branchId, effectiveFrom, day);
            } else {
                jdbc.update("""
                        INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1, open_2, close_2)
                        VALUES (?, ?, ?, '08:00', '12:00', '14:00', '19:00')
                        """, branchId, effectiveFrom, day);
            }
        }
    }

    /** Thứ Hai kế tiếp (luôn sau hôm nay). */
    private static LocalDate nextMonday() {
        LocalDate today = LocalDate.now(ZONE);
        return today.plusDays(8 - today.getDayOfWeek().getValue());
    }

    private static Map<String, Object> slotBody(String group, LocalDate date, String start, int quota) {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceGroup", group);
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        body.put("quota", quota);
        return body;
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    private long staffAccount(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "settings-it-" + UUID.randomUUID() + "@petcare.test", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return id;
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "BranchSettingsIT")).accessToken();
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
