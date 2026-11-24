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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xác thực và phân quyền của {@code POST /shift-requests/{id}/reject} (workforce-v1 #14; docs/adr/0150): 401 mọi dạng,
 * BR-TK-17 trước body, 400 body sai trước 403 vai trò, chỉ BRANCH_MANAGER (ma trận bảy vai trò, kể cả chính người gửi),
 * quản lý chi nhánh khác 403 và <b>không khóa</b> tài khoản của chi nhánh đó, vai trò và chi nhánh đọc lại từ DB ở mỗi
 * request, khóa tạm không chặn phiên đang có, chi nhánh {@code DRAFT} vẫn từ chối được, người gửi đã điều chuyển thì
 * chỉ quản lý chi nhánh <b>cũ</b> (chi nhánh của phân ca) từ chối được, trường chi nhánh lạ bị bỏ qua, mọi từ chối quyền
 * không để lại dấu vết. Era <b>năm 2077</b>. Đổi vai trò / chi nhánh bằng SQL (như các IT bước 3–8): qua API sẽ vướng
 * BR-QT-04 và kích hoạt listener BR-NS-09, không phải thứ đang kiểm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestRejectSecurityIT.TestBeans.class})
class ShiftRequestRejectSecurityIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2077-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2077, 3, 21);

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
    private long managerB;
    private long staffA;
    private long staffB;
    private long assignmentA;
    private long requestA;
    private long requestB;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        staffA = data.staff("RECEPTIONIST", branchA);
        staffB = data.staff("RECEPTIONIST", branchB);
        long shiftA = definition(branchA, "Ca A", "08:00", "12:00", true);
        long shiftB = definition(branchB, "Ca B", "08:00", "12:00", true);
        assignmentA = data.assignment(branchA, staffA, shiftA, DAY, "SCHEDULED", "SELF", null);
        requestA = pendingRequest(assignmentA, staffA);
        long assignmentB = data.assignment(branchB, staffB, shiftB, DAY, "SCHEDULED", "SELF", null);
        requestB = pendingRequest(assignmentB, staffB);
        before = trace();
    }

    @AfterEach
    void tearDown() {
        purgeRejectedOutbox(staffA, staffB);
        finish();
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenRejectIs401AndLeavesNoTrace() {
        for (String token : new String[] {null, "", "rac.khong.phai.token"}) {
            ResponseEntity<Map<String, Object>> response = reject(token, requestA, NOTE);

            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aRevokedSessionALockedOrDisabledAccountAndAnExpiredAccessTokenAre401() {
        String revoked = token(managerA);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", managerA);
        assertThat(reject(revoked, requestA, NOTE).getStatusCode().value()).isEqualTo(401);

        long lockedManager = data.staff("BRANCH_MANAGER", branchA);
        String lockedToken = token(lockedManager);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", lockedManager);
        assertThat(reject(lockedToken, requestA, NOTE).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", lockedManager);
        assertThat(reject(lockedToken, requestA, NOTE).getStatusCode().value()).isEqualTo(401);

        long agingManager = data.staff("BRANCH_MANAGER", branchA);
        String aging = token(agingManager);
        // Profile test đặt session.access_ttl_minutes = 720 (V9000), nên qua mốc 10 phút chưa hết hạn: dời quá 12 giờ.
        clock.set(NOW.plus(Duration.ofHours(13)));
        assertThat(reject(aging, requestA, NOTE).getStatusCode().value()).isEqualTo(401);

        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aTemporaryLoginLockDoesNotCutAnExistingSessionSoTheManagerCanStillReject() {
        String token = token(managerA);
        jdbc.update("UPDATE accounts SET locked_until = now() + interval '1 hour' WHERE id = ?", managerA);

        assertThat(reject(token, requestA, NOTE).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeAnyBodyCheck() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerA);
        String token = token(managerA);

        assertBusinessRule(reject(token, requestA, NOTE), "BR-TK-17");
        assertBusinessRule(rawReject(token, requestA, "{}"), "BR-TK-17");
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyABranchManagerMayRejectEveryOtherRoleIs403AndLeavesNoTrace() {
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();

        for (long denied : new long[] {staffA, vet, caretaker, admin, superManager, customer}) {
            ResponseEntity<Map<String, Object>> response = reject(token(denied), requestA, NOTE);

            assertThat(response.getStatusCode().value()).as("account=%d", denied).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(trace()).as("kể cả chính người gửi cũng không tự từ chối được").isEqualTo(before);

        assertThat(reject(token(managerA), requestA, NOTE).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aMalformedBodyIs400EvenForARoleThatWouldBe403ButAWellFormedBodyIs403() {
        String staffToken = token(staffA);

        ResponseEntity<Map<String, Object>> malformed = rawReject(staffToken, requestA, "{}");
        ResponseEntity<Map<String, Object>> wellFormed = reject(staffToken, requestA, NOTE);

        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(malformed)).isEqualTo("VALIDATION_FAILED");
        assertThat(wellFormed.getStatusCode().value()).isEqualTo(403);
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void theRoleIsReadFromTheDatabaseOnEveryRequestSoAChangeTakesEffectImmediately() {
        String staffToken = token(staffA);
        assertThat(reject(staffToken, requestA, NOTE).getStatusCode().value()).isEqualTo(403);

        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", staffA);
        assertThat(reject(staffToken, requestA, NOTE).getStatusCode().value()).as("được thăng chức giữa phiên")
                .isEqualTo(200);

        long second = data.assignment(branchA, staffA, definition(branchA, "Ca C", "13:00", "17:00", true), DAY,
                "SCHEDULED", "SELF", null);
        long secondRequest = data.leaveRequest(second, staffA, "PENDING", null);
        String managerToken = token(managerA);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", managerA);
        assertThat(reject(managerToken, secondRequest, NOTE).getStatusCode().value()).as("bị giáng chức giữa phiên")
                .isEqualTo(403);
        assertThat(fullRequestRow(secondRequest)).containsEntry("status", "PENDING");
    }

    // ---------------------------------------------------------------- chi nhánh

    @Test
    void aManagerOfAnotherBranchGets403AndNeverLocksTheOtherBranchsAccounts() {
        String token = token(managerB);

        ResponseEntity<Map<String, Object>> response = reject(token, requestA, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(message(response)).isEqualTo("Không có quyền truy cập dữ liệu này");
        assertThat(trace()).isEqualTo(before);
        // Phạm vi kiểm bằng đọc không khóa trước mọi khóa: dòng tài khoản của nhân viên chi nhánh A lấy được ngay.
        assertThat(canLockNowait("accounts", staffA)).isTrue();
        assertThat(canLockNowait("shift_requests", requestA)).isTrue();
    }

    @Test
    void anotherBranchsRequestIs403EvenWhenItIsAlreadyClosedBecauseScopeComesBeforeTheStateCheck() {
        long closed = data.leaveRequest(assignmentA, staffA, "WITHDRAWN", null);

        ResponseEntity<Map<String, Object>> response = reject(token(managerB), closed, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void aManagerMovedToAnotherBranchMidSessionLosesTheOldBranchAtOnceWithTheSameToken() {
        String token = token(managerA);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, managerA);

        assertThat(reject(token, requestA, NOTE).getStatusCode().value()).isEqualTo(403);
        assertThat(reject(token, requestB, NOTE).getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(requestA)).containsEntry("status", "PENDING");
    }

    @Test
    void aRequesterWhoMovedBranchLeavesTheRequestWithTheBranchOfTheAssignmentSoOnlyTheOldManagerCanReject() {
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, staffA);

        assertThat(reject(token(managerB), requestA, NOTE).getStatusCode().value()).as("chi nhánh hiện tại của người gửi")
                .isEqualTo(403);
        assertThat(reject(token(managerA), requestA, NOTE).getStatusCode().value()).as("chi nhánh của phân ca")
                .isEqualTo(200);
    }

    @Test
    void aManagerOfADraftBranchMayStillRejectTheRequestsOfThatBranch() {
        long draft = data.branch();
        jdbc.update("UPDATE branches SET status = 'DRAFT' WHERE id = ?", draft);
        long draftManager = data.staff("BRANCH_MANAGER", draft);
        long draftStaff = data.staff("RECEPTIONIST", draft);
        long shift = definition(draft, "Ca D", "08:00", "12:00", true);
        long request = pendingRequest(data.assignment(draft, draftStaff, shift, DAY, "SCHEDULED", "SELF", null),
                draftStaff);

        try {
            assertThat(reject(token(draftManager), request, NOTE).getStatusCode().value()).isEqualTo(200);
        } finally {
            purgeRejectedOutbox(draftStaff);
        }
    }

    @Test
    void aBranchIdInTheBodyOrTheQueryIsIgnored() {
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> viaBody = rawReject(token, requestB,
                "{'decisionNote':'" + NOTE + "','branchId':" + branchB + "}");
        ResponseEntity<Map<String, Object>> viaQuery = call(HttpMethod.POST,
                rejectPath(requestB) + "?branchId=" + branchB, token, rejectBody(NOTE));

        assertThat(viaBody.getStatusCode().value()).isEqualTo(403);
        assertThat(viaQuery.getStatusCode().value()).isEqualTo(403);
        assertThat(fullRequestRow(requestB)).containsEntry("status", "PENDING");
    }

    // ---------------------------------------------------------------- loại nội dung và phương thức

    @Test
    void aWrongContentTypeIs415AndAWrongMethodIs405AndNeitherLeavesATrace() {
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> text = http.exchange(rejectPath(requestA), HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("decisionNote=x", headers(token, MediaType.TEXT_PLAIN)),
                MAP);
        assertThat(text.getStatusCode().value()).isEqualTo(415);
        assertThat(errorCode(text)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            // GET / DELETE không mang body (HttpURLConnection từ chối); PUT / PATCH mang body như một lần gọi thật.
            String json = method == HttpMethod.PUT || method == HttpMethod.PATCH ? rejectBody(NOTE) : null;
            ResponseEntity<Map<String, Object>> response = call(method, rejectPath(requestA), token, json);
            assertThat(response.getStatusCode().value()).as(method.name()).isEqualTo(405);
            assertThat(errorCode(response)).isEqualTo("METHOD_NOT_ALLOWED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- danh tính của người quyết định

    @Test
    void theDeciderAndTheAuditActorAreAlwaysTheCallerWhateverTheBodySays() {
        ResponseEntity<Map<String, Object>> response = rawReject(token(managerA), requestA,
                "{'decisionNote':'" + NOTE + "','decidedBy':" + managerB + "}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(requestA)).containsEntry("decided_by", managerA);
        assertThat(jdbc.queryForObject("""
                SELECT actor_account_id FROM audit_logs
                WHERE entity_type = 'shift_requests' AND entity_id = ?""", Long.class, requestA)).isEqualTo(managerA);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> reject(String token, long requestId, String note) {
        return rawReject(token, requestId, rejectBody(note));
    }

    private ResponseEntity<Map<String, Object>> rawReject(String token, long requestId, String json) {
        return call(HttpMethod.POST, rejectPath(requestId), token, json);
    }

    private static HttpHeaders headers(String token, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setBearerAuth(token);
        return headers;
    }

    private boolean canLockNowait(String table, long id) {
        try {
            tx.execute(status -> jdbc.queryForObject(
                    "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", Long.class, id));
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    /** Dấu vết có thể bị lần từ chối đụng tới: yêu cầu, phân ca (kể cả {@code xmin}), outbox, audit. */
    private String trace() {
        return jdbc.queryForList("""
                SELECT r.id || ':' || r.status || ':' || r.xmin::text FROM shift_requests r
                JOIN shift_assignments a ON a.id = r.assignment_id WHERE a.branch_id IN (?, ?) ORDER BY r.id""",
                String.class, branchA, branchB)
                + "|assignments=" + jdbc.queryForList("""
                SELECT id || ':' || status || ':' || xmin::text FROM shift_assignments
                WHERE branch_id IN (?, ?) ORDER BY id""", String.class, branchA, branchB)
                + "|outbox=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id IN (?, ?)""", Integer.class,
                staffA, staffB)
                + "|audit=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_REQUEST_REJECTED' AND entity_id IN (?, ?)""",
                Integer.class, requestA, requestB);
    }
}
