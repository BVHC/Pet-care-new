package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import com.petcare.module.identity.service.SessionService;

/**
 * T45 — 2 chuyển của bảng "2. Chi nhánh" (docs/03-state-machines.md) qua endpoint thật, <b>không stand-in nào</b>
 * (IT cũ {@code BranchAdminIT} mock 7 bean, kể cả {@code StaffDirectoryApi}): BRANCH_MANAGER của điều kiện kích hoạt
 * được tạo bằng {@code POST /api/staff}. Giờ mở cửa là dữ liệu nền nên chèn SQL (BRANCH_MANAGER mới tạo còn bị
 * BR-TK-17 chặn tới khi đổi mật khẩu). Không job nào chạm chi nhánh nên không cần era riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BranchFsmIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> branches = new ArrayList<>();
    private String superManager;

    @BeforeEach
    void setUp() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'hash', 'SUPER_MANAGER', 'ACTIVE') RETURNING id
                """, Long.class, "branch-fsm-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test");
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Quản lý chuỗi IT', NULL)",
                id);
        superManager = new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(id, "203.0.113.9", "BranchFsmIT")).accessToken();
    }

    @AfterEach
    void tearDown() {
        for (long id : branches) {
            jdbc.update("DELETE FROM opening_hours WHERE branch_id = ?", id);
            jdbc.update("DELETE FROM staff_profiles WHERE branch_id = ?", id);
            jdbc.update("DELETE FROM branches WHERE id = ?", id);
        }
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — tạo chi nhánh: {@code DRAFT}, chưa hiển thị công khai. */
    @Test
    void createdBranchIsDraftAndNotPublic() {
        long id = create();

        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(publicView(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** #2 — kích hoạt khi có ≥ 1 BRANCH_MANAGER {@code ACTIVE} (tạo qua API) và giờ mở cửa: {@code ACTIVE}, hiện công khai. */
    @Test
    void activatingWithAManagerAndOpeningHoursMakesItPublic() {
        long id = create();
        createManager(id);
        openingHours(id);

        ResponseEntity<Map<String, Object>> response = activate(id);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT activated_at IS NOT NULL FROM branches WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(publicView(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================================================================ điều kiện của #2 (400) và cặp sai (409)

    @Test
    void activatingWithoutAManagerIs400AndWritesNothing() {
        long id = create();
        openingHours(id);
        String before = version(id);

        ResponseEntity<Map<String, Object>> response = activate(id);

        assertRule(response, "BR-QT-04");
        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(version(id)).isEqualTo(before);
    }

    @Test
    void activatingWithoutOpeningHoursIs400AndWritesNothing() {
        long id = create();
        createManager(id);
        String before = version(id);

        ResponseEntity<Map<String, Object>> response = activate(id);

        assertRule(response, "BR-CN-01");
        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(version(id)).isEqualTo(before);
    }

    /** Cặp sai duy nhất có đường HTTP: {@code ACTIVE → ACTIVE}. Tầng 3 chưa có tạm ngừng / đóng cửa nên không có cạnh ra. */
    @Test
    void activatingAnActiveBranchIs409AndWritesNothing() {
        long id = create();
        createManager(id);
        openingHours(id);
        assertThat(activate(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        String before = version(id);

        ResponseEntity<Map<String, Object>> response = activate(id);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                .containsEntry("statusCode", 409);
        assertThat(version(id)).isEqualTo(before);
    }

    // ================================================================ helpers

    private long create() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "FSM chi nhánh " + UUID.randomUUID());
        body.put("address", "1 Lê Lợi, Quận 1");
        body.put("phone", "0280000000");
        body.put("latitude", 10.76);
        body.put("longitude", 106.66);
        body.put("acceptsAfterHoursEmergency", false);
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/branches", superManager, body);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        long id = ((Number) map(response.getBody().get("data")).get("branchId")).longValue();
        branches.add(id);
        return id;
    }

    private void createManager(long branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", "bm-" + UUID.randomUUID() + "@petcare.test");
        body.put("phone", "0901234567");
        body.put("fullName", "Quản lý IT");
        body.put("role", "BRANCH_MANAGER");
        body.put("branchId", branchId);
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/staff", superManager, body);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private void openingHours(long branchId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2000-01-01', ?, TIME '08:00', TIME '17:00')""", branchId, day);
        }
    }

    private ResponseEntity<Map<String, Object>> activate(long id) {
        return call(HttpMethod.POST, "/api/branches/" + id + "/activate", superManager, null);
    }

    private ResponseEntity<Map<String, Object>> publicView(long id) {
        return call(HttpMethod.GET, "/api/public/branches/" + id, null, null);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM branches WHERE id = ?", String.class, id);
    }

    private String version(long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM branches WHERE id = ?", String.class, id);
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
