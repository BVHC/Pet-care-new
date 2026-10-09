package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.PublicVetProfile;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * {@code StaffDirectoryApi} thật trên Postgres 17 (docs/adr/0024): đếm BRANCH_MANAGER {@code ACTIVE} kể cả đang bị khóa
 * (BR-QT-04), "đang hoạt động" = {@code ACTIVE} và không khóa, online theo {@code staff.online_window_minutes} [CFG]
 * (BR-TN-05, 06), VET công khai (BR-TK-20). Kích hoạt chi nhánh qua HTTP <b>không mock</b> module nào: hết 500 của
 * placeholder cũ. Mỗi test dùng chi nhánh mới; các câu không theo chi nhánh chỉ kiểm id do test tạo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffDirectoryIT.TestBeans.class})
class StaffDirectoryIT {

    private static final Instant T0 = Instant.parse("2026-10-09T03:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private StaffDirectoryApi directory;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long branch;
    private long otherBranch;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        branch = branchRow("DRAFT");
        otherBranch = branchRow("DRAFT");
    }

    @Test
    void countsActiveBranchManagersIncludingLockedOnes() {
        staff(Role.BRANCH_MANAGER, branch, "ACTIVE", false, null);
        staff(Role.BRANCH_MANAGER, branch, "ACTIVE", true, null);
        staff(Role.BRANCH_MANAGER, branch, "DISABLED", false, null);
        staff(Role.RECEPTIONIST, branch, "ACTIVE", false, null);
        staff(Role.BRANCH_MANAGER, otherBranch, "ACTIVE", false, null);

        assertThat(directory.countBranchManagers(branch)).isEqualTo(2);
        assertThat(directory.countBranchManagers(otherBranch)).isEqualTo(1);
        assertThat(directory.countBranchManagers(branchRow("DRAFT"))).isZero();
    }

    @Test
    void assignableStaffAreActiveUnlockedOfBranchAndRoleWithOnlineFirst() {
        long offline = staff(Role.VET, branch, "ACTIVE", false, null, "An");
        long online = staff(Role.VET, branch, "ACTIVE", false, T0.minus(Duration.ofMinutes(10)), "Bình");
        long stale = staff(Role.VET, branch, "ACTIVE", false, T0.minus(Duration.ofMinutes(11)), "Chi");
        staff(Role.VET, branch, "ACTIVE", true, T0, "Khóa");
        staff(Role.VET, branch, "DISABLED", false, T0, "Vô hiệu");
        staff(Role.CARETAKER, branch, "ACTIVE", false, T0, "Chăm sóc");
        staff(Role.VET, otherBranch, "ACTIVE", false, T0, "Chi nhánh khác");

        assertThat(directory.findAssignableStaff(branch, Role.VET))
                .extracting(StaffSummary::accountId, StaffSummary::online, StaffSummary::active)
                .containsExactly(
                        tuple(online, true, true),
                        tuple(offline, false, true),
                        tuple(stale, false, true));
        assertThat(directory.findActiveStaffIds(branch, Role.VET)).containsExactly(offline, online, stale);

        clock.advance(Duration.ofMinutes(1));
        assertThat(directory.findAssignableStaff(branch, Role.VET)).noneMatch(StaffSummary::online);
    }

    @Test
    void findStaffReportsLockedAsInactiveAndIgnoresCustomers() {
        long locked = staff(Role.RECEPTIONIST, branch, "ACTIVE", true, T0, "Lễ tân");
        long customer = customerAccount();

        assertThat(directory.findStaff(locked)).contains(
                new StaffSummary(locked, "Lễ tân", Role.RECEPTIONIST, branch, false, true));
        assertThat(directory.findStaff(customer)).isEmpty();
        assertThat(directory.findEmail(customer)).hasValueSatisfying(email -> assertThat(email).endsWith("@petcare.test"));
        assertThat(directory.findEmail(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void superManagersAreActiveAndUnlockedWithoutNeedingAProfile() {
        long active = account(Role.SUPER_MANAGER, "ACTIVE", false);
        long locked = account(Role.SUPER_MANAGER, "ACTIVE", true);
        long disabled = account(Role.SUPER_MANAGER, "DISABLED", false);

        assertThat(directory.findActiveSuperManagerIds()).contains(active).doesNotContain(locked, disabled);
    }

    @Test
    void publicVetsAreActiveUnlockedVetsWithTheirProfile() {
        long vet = staff(Role.VET, branch, "ACTIVE", false, null, "Bác sĩ Công khai");
        jdbc.update("UPDATE staff_profiles SET avatar_url = ?, specialty = ?, bio = ? WHERE account_id = ?",
                "https://img.petcare.test/a.png", "Nội khoa", "Mười năm kinh nghiệm", vet);
        long locked = staff(Role.VET, branch, "ACTIVE", true, null);
        long disabled = staff(Role.VET, branch, "DISABLED", false, null);
        long caretaker = staff(Role.CARETAKER, branch, "ACTIVE", false, null);

        assertThat(directory.listPublicVets())
                .contains(new PublicVetProfile(vet, "Bác sĩ Công khai", "https://img.petcare.test/a.png", "Nội khoa",
                        "Mười năm kinh nghiệm", branch))
                .extracting(PublicVetProfile::accountId).doesNotContain(locked, disabled, caretaker);
    }

    /** Chi nhánh#2 qua HTTP, không mock: trước đây placeholder làm endpoint này trả 500. */
    @Test
    void branchActivationUsesTheRealDirectory() {
        hoursRows(branch);
        String superManager = token(account(Role.SUPER_MANAGER, "ACTIVE", false));
        String path = "/api/branches/" + branch + "/activate";

        assertRule(call(path, superManager), "BR-QT-04");

        staff(Role.BRANCH_MANAGER, branch, "DISABLED", false, null);
        staff(Role.BRANCH_MANAGER, otherBranch, "ACTIVE", false, null);
        assertRule(call(path, superManager), "BR-QT-04");
        assertThat(statusOf(branch)).isEqualTo("DRAFT");

        staff(Role.BRANCH_MANAGER, branch, "ACTIVE", true, null);
        ResponseEntity<Map<String, Object>> activated = call(path, superManager);
        assertThat(activated.getStatusCode()).as("%s", activated.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(branch)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ helpers

    private long branchRow(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, "StaffDirectoryIT " + UUID.randomUUID(), status);
    }

    private void hoursRows(long branchId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2026-10-01', ?, ?, ?)
                    """, branchId, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        }
    }

    private long account(Role role, String status, boolean locked) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status, is_locked)
                VALUES (?, '0900000000', 'hash', ?, ?, ?) RETURNING id
                """, Long.class, "staff-dir-it-" + UUID.randomUUID() + "@petcare.test", role.name(), status, locked);
    }

    private long staff(Role role, Long branchId, String status, boolean locked, Instant lastSeenAt) {
        return staff(role, branchId, status, locked, lastSeenAt, "Nhân viên IT");
    }

    private long staff(Role role, Long branchId, String status, boolean locked, Instant lastSeenAt, String name) {
        long id = account(role, status, locked);
        if (lastSeenAt != null) {
            jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(lastSeenAt), id);
        }
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id, name,
                branchId);
        return id;
    }

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "staff-dir-it-c-" + UUID.randomUUID() + "@petcare.test");
    }

    private String statusOf(long branchId) {
        return jdbc.queryForObject("SELECT status FROM branches WHERE id = ?", String.class, branchId);
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "StaffDirectoryIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("errorCode", "BUSINESS_RULE_VIOLATION").containsEntry("statusCode", 400)
                .containsKeys("message", "timestamp", "traceId");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }
}
