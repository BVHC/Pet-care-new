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
 * T14 phần 2, 3, 5 trên đúng bean của app thật: {@code VisitQueryApi}, {@code CashierShiftQueryApi} đã có bản thật
 * (D016 trả ở T30, docs/adr/0064): điều chuyển / vô hiệu hóa / khóa A05–A08 chạy thật, còn ca thu ngân mở thì BR-QT-08; các nhánh lỗi
 * đứng trước BR-QT-08, đổi chức vụ (docs/adr/0031 Q4), vô hiệu hóa SUPER_MANAGER (docs/adr/0033 Q2) và kích hoạt lại. T14 phần 5
 * (docs/adr/0035): khóa A05–A08 chạy thật; khóa ADMIN / SUPER_MANAGER và mở khóa chạy
 * thật.
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
    void transferOfBranchStaffWorksOnRealBeans() {
        long vet = staff("VET", branchA);

        ResponseEntity<Map<String, Object>> response = post("/api/staff/" + vet + "/transfer",
                Map.of("branchId", branchB));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT branch_id FROM staff_profiles WHERE account_id = ?", Long.class, vet))
                .isEqualTo(branchB);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'STAFF_TRANSFERRED' AND entity_id = ?""", Long.class,
                vet)).isEqualTo(1L);
    }

    /** T30 (docs/adr/0064): lễ tân còn ca thu ngân {@code OPEN} → BR-QT-08 trên bean thật, không ghi gì. */
    @Test
    void transferOfReceptionistWithOpenShiftIsBrQt08AndWritesNothing() {
        long receptionist = staff("RECEPTIONIST", branchA);
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, business_date, status, opened_at)
                VALUES (?, ?, current_date, 'OPEN', now()) RETURNING id""", Long.class, branchA, receptionist);
        try {
            ResponseEntity<Map<String, Object>> response = post("/api/staff/" + receptionist + "/transfer",
                    Map.of("branchId", branchB));

            assertBadRequestEndingWith(response, "BR-QT-08");
            assertThat(jdbc.queryForObject("SELECT branch_id FROM staff_profiles WHERE account_id = ?", Long.class,
                    receptionist)).isEqualTo(branchA);
        } finally {
            jdbc.update("DELETE FROM cashier_shifts WHERE id = ?", shift);
        }
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

    /** T14 phần 3 (docs/adr/0033): vô hiệu hóa A05–A08 chạy thật khi hết việc dở (D016 đã trả, T30). */
    @Test
    void disableBranchStaffWorksOnRealBeans() {
        long vet = staff("VET", branchA);
        String vetToken = tokenOf(vet);

        ResponseEntity<Map<String, Object>> response = post("/api/staff/" + vet + "/disable", null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, vet))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL""", Long.class, vet))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'STAFF_DISABLED' AND entity_id = ?""", Long.class,
                vet)).isEqualTo(1L);
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

    // ================================================================ T14 phần 5 — khóa / mở khóa (docs/adr/0035)

    /**
     * Khóa nhân viên A05–A08 chạy thật khi không còn việc dở (D016 đã trả, T30): khóa dòng, thu hồi phiên, ghi audit.
     * Khóa khách không kiểm ở đây (phụ thuộc {@code BoardingQueryApi}, nợ D033).
     */
    @Test
    void lockingBranchStaffWorksOnRealBeans() {
        long admin = staff("ADMIN", null);
        long vet = staff("VET", branchA);
        tokenOf(vet);

        ResponseEntity<Map<String, Object>> response = postAs(admin, "/api/accounts/" + vet + "/lock",
                Map.of("reason", "Vi phạm"));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vet)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL""", Long.class, vet))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_LOCKED' AND entity_id = ?""", Long.class,
                vet)).isEqualTo(1L);
    }

    /** Receptionist còn ca thu ngân {@code OPEN}: khóa vẫn thành công và liệt kê ca trong {@code pendingWork}. */
    @Test
    void lockingReceptionistWithOpenShiftListsTheShift() {
        long admin = staff("ADMIN", null);
        long receptionist = staff("RECEPTIONIST", branchA);
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, business_date, status, opened_at)
                VALUES (?, ?, current_date, 'OPEN', now()) RETURNING id""", Long.class, branchA, receptionist);
        try {
            ResponseEntity<Map<String, Object>> response = postAs(admin, "/api/accounts/" + receptionist + "/lock",
                    Map.of("reason", "Vi phạm"));

            assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(String.valueOf(response.getBody())).contains("CASHIER_SHIFT_OPEN");
        } finally {
            jdbc.update("DELETE FROM cashier_shifts WHERE id = ?", shift);
        }
    }


    /** ADMIN / SUPER_MANAGER không hỏi interface nào → khóa và mở khóa chạy ngay trên app thật. */
    @Test
    void adminLocksAndUnlocksSuperManagerOnRealBeans() {
        long admin = staff("ADMIN", null);
        long target = staff("SUPER_MANAGER", null);
        String targetToken = tokenOf(target);

        ResponseEntity<Map<String, Object>> locked = postAs(admin, "/api/accounts/" + target + "/lock",
                Map.of("reason", "Sự cố bảo mật"));
        assertThat(locked.getStatusCode()).as("%s", locked.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(targetToken);
        assertThat(http.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map<String, Object>> unlocked = postAs(admin, "/api/accounts/" + target + "/unlock",
                Map.of("reason", "Đã xác minh"));
        assertThat(unlocked.getStatusCode()).as("%s", unlocked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, target))
                .isFalse();
    }

    /** Mở khóa không hỏi interface nào → chạy được với mọi tài khoản, kể cả nhân viên chi nhánh. */
    @Test
    void unlockBranchStaffWorksOnRealBeans() {
        long admin = staff("ADMIN", null);
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'Vi phạm' WHERE id = ?", vet);

        ResponseEntity<Map<String, Object>> unlocked = postAs(admin, "/api/accounts/" + vet + "/unlock",
                Map.of("reason", "Xong"));

        assertThat(unlocked.getStatusCode()).as("%s", unlocked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vet)).isFalse();
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
