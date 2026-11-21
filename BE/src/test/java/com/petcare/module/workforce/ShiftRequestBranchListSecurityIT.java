package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xác thực và phân quyền của {@code GET /shift-requests} (workforce-v1 #12; docs/adr/0138 bước 8): 401 mọi dạng, BR-TK-17
 * trước tham số, 400 tham số sai trước 403 vai trò, chỉ BRANCH_MANAGER (ma trận bảy vai trò), vai trò và chi nhánh đọc
 * lại từ DB ở mỗi request (đổi vai trò / điều chuyển giữa phiên), khóa tạm không chặn phiên đang có, chi nhánh
 * {@code DRAFT} vẫn xem được, tham số chi nhánh lạ bị bỏ qua và từ chối không để lại dấu vết. Era <b>năm 2075</b>.
 * Đổi vai trò / chi nhánh của quản lý bằng SQL (như các IT bước 3–4): qua API sẽ vướng BR-QT-04 và kích hoạt listener
 * BR-NS-09, không phải thứ đang kiểm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestBranchListSecurityIT.TestBeans.class})
class ShiftRequestBranchListSecurityIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2075-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2075, 3, 21);
    private static final String LIST = "/api/shift-requests";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branchA;
    private long branchB;
    private long managerA;
    private long staffA;
    private long staffB;
    private long requestA;
    private long requestB;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        staffA = data.staff("RECEPTIONIST", branchA);
        staffB = data.staff("RECEPTIONIST", branchB);
        long shiftA = definition(branchA, "Ca A", "08:00", "12:00", true);
        long shiftB = definition(branchB, "Ca B", "08:00", "12:00", true);
        requestA = data.leaveRequest(data.assignment(branchA, staffA, shiftA, DAY, "SCHEDULED", "SELF", null), staffA,
                "PENDING", null);
        requestB = data.leaveRequest(data.assignment(branchB, staffB, shiftB, DAY, "SCHEDULED", "SELF", null), staffB,
                "PENDING", null);
        before = trace();
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenTheListIs401AndLeavesNoTrace() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, LIST, token);

            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aRevokedSessionALockedOrDisabledAccountAndAnExpiredAccessTokenAre401() {
        String revoked = token(managerA);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", managerA);
        assertThat(call(HttpMethod.GET, LIST, revoked).getStatusCode().value()).isEqualTo(401);

        long lockedManager = data.staff("BRANCH_MANAGER", branchA);
        String lockedToken = token(lockedManager);
        assertThat(call(HttpMethod.GET, LIST, lockedToken).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", lockedManager);
        assertThat(call(HttpMethod.GET, LIST, lockedToken).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", lockedManager);
        assertThat(call(HttpMethod.GET, LIST, lockedToken).getStatusCode().value()).isEqualTo(401);

        long agingManager = data.staff("BRANCH_MANAGER", branchA);
        String aging = token(agingManager);
        assertThat(call(HttpMethod.GET, LIST, aging).getStatusCode().value()).isEqualTo(200);
        // Profile test đặt session.access_ttl_minutes = 720 (V9000), nên qua mốc 10 phút chưa hết hạn: dời quá 12 giờ.
        clock.set(NOW.plus(Duration.ofHours(13)));
        assertThat(call(HttpMethod.GET, LIST, aging).getStatusCode().value()).isEqualTo(401);

        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aTemporaryLoginLockDoesNotCutAnExistingSession() {
        String token = token(managerA);
        jdbc.update("UPDATE accounts SET locked_until = now() + interval '1 hour' WHERE id = ?", managerA);

        assertThat(call(HttpMethod.GET, LIST, token).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeAnyParameterCheck() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerA);
        String token = token(managerA);

        assertBusinessRule(call(HttpMethod.GET, LIST, token), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.GET, LIST + "?status=FOO", token), "BR-TK-17");
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyABranchManagerMayListEveryOtherRoleIs403() {
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();

        ResponseEntity<Map<String, Object>> allowed = call(HttpMethod.GET, LIST, token(managerA));
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(allowed))).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(requestA);
        for (long denied : new long[] {staffA, vet, caretaker, admin, superManager, customer}) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, LIST, token(denied));

            assertThat(response.getStatusCode().value()).as("account=%d", denied).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aMalformedParameterIs400EvenForARoleThatWouldBe403() {
        String superManager = token(data.staff("SUPER_MANAGER", null));

        ResponseEntity<Map<String, Object>> malformed = call(HttpMethod.GET, LIST + "?status=FOO", superManager);
        ResponseEntity<Map<String, Object>> wellFormed = call(HttpMethod.GET, LIST, superManager);

        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(malformed)).isEqualTo("MALFORMED_REQUEST");
        assertThat(wellFormed.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void theRoleIsReadFromTheDatabaseOnEveryRequestSoAChangeTakesEffectImmediately() {
        String staffToken = token(staffA);
        assertThat(call(HttpMethod.GET, LIST, staffToken).getStatusCode().value()).isEqualTo(403);

        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", staffA);
        ResponseEntity<Map<String, Object>> promoted = call(HttpMethod.GET, LIST, staffToken);

        assertThat(promoted.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(promoted))).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(requestA);

        String managerToken = token(managerA);
        assertThat(call(HttpMethod.GET, LIST, managerToken).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", managerA);
        assertThat(call(HttpMethod.GET, LIST, managerToken).getStatusCode().value()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- chi nhánh

    @Test
    void aManagerMovedToAnotherBranchMidSessionOnlySeesTheNewBranchWithTheSameToken() {
        String token = token(managerA);
        assertThat(ids(call(HttpMethod.GET, LIST, token))).containsExactly(requestA);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, managerA);

        assertThat(ids(call(HttpMethod.GET, LIST, token))).containsExactly(requestB);
    }

    @Test
    void aBranchIdParameterOfAnotherBranchIsIgnored() {
        String token = token(managerA);

        assertThat(ids(call(HttpMethod.GET, LIST + "?branchId=" + branchB, token))).containsExactly(requestA);
        assertThat(ids(call(HttpMethod.GET, LIST + "?branchId=999999999", token))).containsExactly(requestA);
    }

    @Test
    void aManagerOfADraftBranchMayListAndSimplyGetsWhatTheBranchHas() {
        long draft = data.branch();
        jdbc.update("UPDATE branches SET status = 'DRAFT' WHERE id = ?", draft);
        long draftManager = data.staff("BRANCH_MANAGER", draft);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, LIST, token(draftManager));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(response))).isEmpty();
    }

    // ---------------------------------------------------------------- hạ tầng

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return contentOf(dataOf(response)).stream().map(item -> idOf(item, "shiftRequestId")).toList();
    }

    /** Dấu vết có thể bị #12 (hoặc lần từ chối) đụng tới: yêu cầu, phân ca (kể cả {@code xmin}), outbox, audit. */
    private String trace() {
        return jdbc.queryForList("""
                SELECT r.id || ':' || r.status || ':' || r.xmin::text FROM shift_requests r
                JOIN shift_assignments a ON a.id = r.assignment_id WHERE a.branch_id IN (?, ?) ORDER BY r.id""",
                String.class, branchA, branchB)
                + "|assignments=" + jdbc.queryForList("""
                SELECT id || ':' || status || ':' || xmin::text FROM shift_assignments
                WHERE branch_id IN (?, ?) ORDER BY id""", String.class, branchA, branchB)
                + "|outbox=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id IN (?, ?, ?)""", Integer.class,
                managerA, staffA, staffB)
                + "|audit=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE actor_account_id IN (?, ?, ?)""", Integer.class, managerA, staffA,
                staffB);
    }
}
