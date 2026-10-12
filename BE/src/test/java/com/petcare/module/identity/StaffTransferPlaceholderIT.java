package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;

/**
 * T14 phần 2 trên đúng bean của app thật: {@code VisitQueryApi}, {@code CashierShiftQueryApi} còn là placeholder ném
 * lỗi (nợ D016). Điều chuyển tới bước BR-QT-08 → 500, rollback, không ghi gì; các nhánh lỗi đứng trước BR-QT-08 và
 * đổi chức vụ (không gọi hai interface, docs/adr/0031 Q4) vẫn chạy đúng. Khi hai interface có bản thật, test 500 phải
 * đổi thành ca thành công (D016 *Cách trả*).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StaffTransferPlaceholderIT {

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

    private TransactionTemplate tx;
    private long branchA;
    private long branchB;
    private long superManager;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branchA = branch();
        branchB = branch();
        superManager = staff("SUPER_MANAGER", null);
    }

    @Test
    void transferReachingPendingWorkGuardIs500AndWritesNothing() {
        long vet = staff("VET", branchA);
        Timestamp updated = jdbc.queryForObject("SELECT updated_at FROM staff_profiles WHERE account_id = ?",
                Timestamp.class, vet);

        ResponseEntity<Map<String, Object>> response = post("/api/staff/" + vet + "/transfer",
                Map.of("branchId", branchB));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(jdbc.queryForMap("SELECT branch_id, updated_at FROM staff_profiles WHERE account_id = ?", vet))
                .containsEntry("branch_id", branchA).containsEntry("updated_at", updated);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'STAFF_TRANSFERRED' AND entity_id = ?""", Long.class,
                vet)).isZero();
    }

    @Test
    void errorsBeforeThePendingWorkGuardAndChangeRoleWorkOnRealBeans() {
        long only = staff("BRANCH_MANAGER", branchA);
        long vet = staff("VET", branchA);

        assertBadRequestEndingWith(post("/api/staff/" + only + "/transfer", Map.of("branchId", branchB)), "BR-QT-04");
        assertBadRequestEndingWith(post("/api/staff/" + vet + "/transfer", Map.of("branchId", Long.MAX_VALUE)),
                "BR-QT-03");
        assertThat(post("/api/staff/" + vet + "/transfer", Map.of("branchId", branchA)).getStatusCode())
                .as("đã ở chi nhánh đích: 200 không gọi guard").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> changed = post("/api/staff/" + vet + "/change-role",
                Map.of("role", "CARETAKER"));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT role FROM accounts WHERE id = ?", String.class, vet))
                .isEqualTo("CARETAKER");
    }

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "StaffTransferPlaceholderIT " + UUID.randomUUID());
    }

    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "placeholder-it-" + UUID.randomUUID() + "@petcare.test", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + id, branchId);
        return id;
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body) {
        String token = tx.execute(status -> sessions.open(superManager, "203.0.113.9", "PlaceholderIT")).accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private static void assertBadRequestEndingWith(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }
}
