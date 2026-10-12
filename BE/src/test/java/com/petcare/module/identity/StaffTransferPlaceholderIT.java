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
 * T14 phần 2, 3 trên đúng bean của app thật: {@code VisitQueryApi}, {@code CashierShiftQueryApi} còn là placeholder
 * ném lỗi (nợ D016). Điều chuyển / vô hiệu hóa A05–A08 tới bước BR-QT-08 → 500, rollback, không ghi gì; các nhánh lỗi
 * đứng trước BR-QT-08, đổi chức vụ (docs/adr/0031 Q4), vô hiệu hóa SUPER_MANAGER (docs/adr/0033 Q2) và kích hoạt lại
 * vẫn chạy đúng. Khi hai interface có bản thật, test 500 phải đổi thành ca thành công (D016 *Cách trả*).
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

    /** T14 phần 3 (docs/adr/0033): vô hiệu hóa A05–A08 tới bước BR-QT-08 → 500, rollback, phiên còn sống. */
    @Test
    void disableBranchStaffReachingPendingWorkGuardIs500AndWritesNothing() {
        long vet = staff("VET", branchA);
        String vetToken = tokenOf(vet);

        ResponseEntity<Map<String, Object>> response = post("/api/staff/" + vet + "/disable", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, vet))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL""", Long.class, vet))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'STAFF_DISABLED' AND entity_id = ?""", Long.class,
                vet)).isZero();
        assertThat(vetToken).isNotBlank();
    }

    /** Q2: SUPER_MANAGER không hỏi hai placeholder → ADMIN vô hiệu hóa / kích hoạt lại được ngay trên app thật. */
    @Test
    void adminDisablesAndReactivatesSuperManagerOnRealBeans() {
        long admin = staff("ADMIN", null);
        long target = staff("SUPER_MANAGER", null);

        ResponseEntity<Map<String, Object>> disabled = postAs(admin, "/api/staff/" + target + "/disable", null);
        assertThat(disabled.getStatusCode()).as("%s", disabled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, target))
                .isEqualTo("DISABLED");

        ResponseEntity<Map<String, Object>> reactivated = postAs(admin, "/api/staff/" + target + "/reactivate", null);
        assertThat(reactivated.getStatusCode()).as("%s", reactivated.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, target))
                .isEqualTo("ACTIVE");
    }

    /** Lỗi trước BR-QT-08, người đã DISABLED (409) và kích hoạt lại không chạm placeholder. */
    @Test
    void disableErrorsBeforeGuardAndReactivateWorkOnRealBeans() {
        long only = staff("BRANCH_MANAGER", branchA);
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", vet);

        assertBadRequestEndingWith(post("/api/staff/" + only + "/disable", null), "BR-QT-04");
        assertThat(post("/api/staff/" + vet + "/disable", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map<String, Object>> reactivated = post("/api/staff/" + vet + "/reactivate",
                Map.of("branchId", branchB));
        assertThat(reactivated.getStatusCode()).as("%s", reactivated.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForMap("""
                SELECT a.status, sp.branch_id FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id
                WHERE a.id = ?""", vet)).containsEntry("status", "ACTIVE").containsEntry("branch_id", branchB);
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
        return postAs(superManager, path, body);
    }

    private ResponseEntity<Map<String, Object>> postAs(long actor, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenOf(actor));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private String tokenOf(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "PlaceholderIT")).accessToken();
    }

    private static void assertBadRequestEndingWith(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }
}
