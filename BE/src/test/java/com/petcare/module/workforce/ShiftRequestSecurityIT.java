package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Xác thực và phân quyền của yêu cầu nghỉ / đổi ca (workforce-v1 #9–11; docs/adr/0138 bước 6), khuôn
 * {@code ShiftAssignmentCancelSecurityIT}: 401 trước mọi xử lý, BR-TK-17 trước validation, validation trước vai trò,
 * chỉ A06–A08 (ma trận bảy vai trò × ba endpoint), danh tính luôn là người gọi nên phân ca / yêu cầu / ca đích của người
 * khác là 404 đồng dạng với "không tồn tại" và không khóa dòng tài khoản của họ, vai trò đọc lại từ DB ở request kế
 * tiếp, phân ca cũ của nhân viên đã điều chuyển là 400 chứ không 403 / 404. Mọi lệnh bị từ chối không để lại dấu vết.
 * Era <b>năm 2065</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestSecurityIT.TestBeans.class})
class ShiftRequestSecurityIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2065-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2065, 3, 21);
    private static final long UNKNOWN = Long.MAX_VALUE - 1;
    private static final long WAIT_SECONDS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private long branchA;
    private long branchB;
    private long managerA;
    private long staffA;
    private long colleagueA;
    private long staffB;
    private long shiftA;
    private long shiftB;
    private long assignment;
    private long request;
    private long colleagueAssignment;
    private long colleagueRequest;
    private String before;
    private String colleagueBefore;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        staffA = data.staff("RECEPTIONIST", branchA);
        colleagueA = data.staff("VET", branchA);
        staffB = data.staff("RECEPTIONIST", branchB);
        shiftA = definition(branchA, "Ca A", "08:00", "12:00", true);
        shiftB = definition(branchB, "Ca B", "08:00", "12:00", true);
        assignment = data.assignment(branchA, staffA, shiftA, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staffA);
        colleagueAssignment = data.assignment(branchA, colleagueA, shiftA, DAY, "SCHEDULED", "SELF", null);
        colleagueRequest = pendingRequest(colleagueAssignment, colleagueA);
        before = requestFootprint(assignment, staffA);
        colleagueBefore = requestFootprint(colleagueAssignment, colleagueA);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenEveryEndpointIs401AndLeavesNoTrace() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            for (ResponseEntity<Map<String, Object>> response : allThree(token, assignment, request)) {
                assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
                assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
            }
        }
        assertUntouched();
    }

    @Test
    void aRevokedSessionAndALockedOrDisabledAccountAre401EvenWithAValidToken() {
        String revoked = token(staffA);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", staffA);
        for (ResponseEntity<Map<String, Object>> response : allThree(revoked, assignment, request)) {
            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }

        String other = token(colleagueA);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", colleagueA);
        assertThat(call(HttpMethod.GET, REQUESTS, other).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", colleagueA);
        assertThat(call(HttpMethod.GET, REQUESTS, other).getStatusCode().value()).isEqualTo(401);

        assertUntouched();
    }

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17OnEveryEndpointBeforeValidation() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", staffA);
        String token = token(staffA);

        assertBusinessRule(call(HttpMethod.POST, REQUESTS, token, "{}"), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment)), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.GET, REQUESTS, token), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.POST, withdrawPath(request), token), "BR-TK-17");
        assertUntouched();
    }

    // ---------------------------------------------------------------- vai trò: ma trận bảy vai trò × ba endpoint

    @Test
    void onlyReceptionistVetAndCaretakerMayUseTheThreeEndpointsEveryOtherRoleIs403() {
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();

        // Người dùng đúng vai trò: id không tồn tại cho 404 (đã qua lớp vai trò), danh sách luôn 200.
        for (long allowed : new long[] {staffA, vet, caretaker}) {
            String token = token(allowed);
            assertThat(call(HttpMethod.POST, REQUESTS, token, leaveBody(UNKNOWN)).getStatusCode().value())
                    .isEqualTo(404);
            assertThat(call(HttpMethod.GET, REQUESTS, token).getStatusCode().value()).isEqualTo(200);
            assertThat(call(HttpMethod.POST, withdrawPath(UNKNOWN), token).getStatusCode().value()).isEqualTo(404);
        }
        for (long denied : new long[] {managerA, admin, superManager, customer}) {
            String token = token(denied);
            for (ResponseEntity<Map<String, Object>> response : List.of(
                    call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment)),
                    call(HttpMethod.GET, REQUESTS, token),
                    call(HttpMethod.POST, withdrawPath(request), token))) {
                assertThat(response.getStatusCode().value()).isEqualTo(403);
                assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
            }
        }
        assertUntouched();
    }

    @Test
    void validationRunsBeforeTheRoleCheckSoAnInvalidBodyIs400AndAValidOneIs403() {
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> valid = call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment));
        ResponseEntity<Map<String, Object>> invalid = call(HttpMethod.POST, REQUESTS, token, "{}");

        assertThat(valid.getStatusCode().value()).isEqualTo(403);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(invalid)).isEqualTo("VALIDATION_FAILED");
        assertUntouched();
    }

    @Test
    void theRoleIsReadFromTheDatabaseOnEveryRequestSoAPromotionTakesEffectImmediately() {
        String token = token(staffA);
        assertThat(call(HttpMethod.GET, REQUESTS, token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", staffA);

        assertThat(call(HttpMethod.GET, REQUESTS, token).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment)).getStatusCode().value())
                .isEqualTo(403);
        assertUntouched();
    }

    // ---------------------------------------------------------------- chủ sở hữu: 404 đồng dạng, không lộ, không khóa

    @Test
    void aColleaguesAssignmentAndRequestLookExactlyLikeNonExistentOnesSoNothingLeaks() {
        String token = token(staffA);

        ResponseEntity<Map<String, Object>> foreignAssignment = call(HttpMethod.POST, REQUESTS, token,
                leaveBody(colleagueAssignment));
        ResponseEntity<Map<String, Object>> unknownAssignment = call(HttpMethod.POST, REQUESTS, token,
                leaveBody(UNKNOWN));
        ResponseEntity<Map<String, Object>> foreignRequest = call(HttpMethod.POST, withdrawPath(colleagueRequest),
                token);
        ResponseEntity<Map<String, Object>> unknownRequest = call(HttpMethod.POST, withdrawPath(UNKNOWN), token);

        for (ResponseEntity<Map<String, Object>> response : List.of(foreignAssignment, unknownAssignment,
                foreignRequest, unknownRequest)) {
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(shape(foreignAssignment)).isEqualTo(shape(unknownAssignment));
        assertThat(shape(foreignRequest)).isEqualTo(shape(unknownRequest));
        assertUntouched();
    }

    @Test
    void aStaffMemberOfAnotherBranchGetsTheSame404ForThisBranchsAssignmentRequestAndShift() {
        String token = token(staffB);
        long ownAssignment = data.assignment(branchB, staffB, shiftB, DAY, "SCHEDULED", "SELF", null);

        assertThat(call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment)).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, withdrawPath(request), token).getStatusCode().value()).isEqualTo(404);
        // Ca của chi nhánh A không dùng được làm ca đích cho phân ca của chi nhánh B, và trông như không tồn tại.
        ResponseEntity<Map<String, Object>> foreignTarget = call(HttpMethod.POST, REQUESTS, token,
                swapBody(ownAssignment, shiftA));
        ResponseEntity<Map<String, Object>> unknownTarget = call(HttpMethod.POST, REQUESTS, token,
                swapBody(ownAssignment, UNKNOWN));
        assertThat(foreignTarget.getStatusCode().value()).isEqualTo(404);
        assertThat(shape(foreignTarget)).isEqualTo(shape(unknownTarget));
        assertThat(requestCountOf(ownAssignment)).isZero();
        assertUntouched();
    }

    @Test
    void aForeignAssignmentNeverLocksTheColleaguesAccount() throws Exception {
        // Một transaction khác giữ khóa độc quyền trên dòng tài khoản của đồng nghiệp. Nếu request của staffA lỡ khóa dòng
        // đó trước khi phân quyền dữ liệu thì sẽ treo; vì đọc không khóa và kiểm chủ trước khi khóa nên phải trả 404 ngay.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, colleagueA);
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        try {
            String token = token(staffA);
            Future<Integer> submit = pool.submit(() -> call(HttpMethod.POST, REQUESTS, token,
                    leaveBody(colleagueAssignment)).getStatusCode().value());

            assertThat(submit.get(10, TimeUnit.SECONDS)).isEqualTo(404);
            Future<Integer> withdraw = pool.submit(() -> call(HttpMethod.POST, withdrawPath(colleagueRequest), token)
                    .getStatusCode().value());
            assertThat(withdraw.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        assertUntouched();
    }

    @Test
    void theListNeverShowsAnotherStaffMembersRequestsAndTheBodyCannotChooseTheRequester() {
        ResponseEntity<Map<String, Object>> mine = call(HttpMethod.GET, REQUESTS, token(staffA));
        ResponseEntity<Map<String, Object>> theirs = call(HttpMethod.GET, REQUESTS, token(colleagueA));
        ResponseEntity<Map<String, Object>> others = call(HttpMethod.GET, REQUESTS, token(staffB));

        assertThat(contentOf(dataOf(mine))).extracting(item -> idOf(item, "shiftRequestId")).containsExactly(request);
        assertThat(contentOf(dataOf(theirs))).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(colleagueRequest);
        assertThat(contentOf(dataOf(others))).isEmpty();

        // Client gửi requesterId của đồng nghiệp trên phân ca của chính mình: bị bỏ qua, người gửi vẫn là người gọi.
        long fresh = data.assignment(branchA, staffA, shiftA, DAY.plusDays(3), "SCHEDULED", "SELF", null);
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, REQUESTS, token(staffA),
                "{'shiftAssignmentId':" + fresh + ",'type':'LEAVE','reason':'" + REASON + "','requesterId':"
                        + colleagueA + "}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(fullRequestRow(onlyRequestIdOf(fresh))).containsEntry("requester_id", staffA);
    }

    // ---------------------------------------------------------------- nhân viên đã điều chuyển

    @Test
    void anOldBranchAssignmentOfATransferredStaffMemberIs400NotAScopeError() {
        long old = data.assignment(branchA, staffA, shiftA, DAY.plusDays(2), "CANCELLED", "SELF", managerA);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, staffA);
        String token = token(staffA);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, REQUESTS, token, leaveBody(old));

        assertBusinessRule(response, "BR-NS-04");
        assertThat(requestCountOf(old)).isZero();
        // Danh sách vẫn là lịch sử của chính người đó, kể cả yêu cầu thuộc phân ca của chi nhánh cũ.
        assertThat(contentOf(dataOf(call(HttpMethod.GET, REQUESTS, token))))
                .extracting(item -> idOf(item, "shiftRequestId")).contains(request);
    }

    // ---------------------------------------------------------------- hạ tầng

    private List<ResponseEntity<Map<String, Object>>> allThree(String token, long assignmentId, long requestId) {
        return List.of(call(HttpMethod.POST, REQUESTS, token, leaveBody(assignmentId)),
                call(HttpMethod.GET, REQUESTS, token), call(HttpMethod.POST, withdrawPath(requestId), token));
    }

    /** Dạng lỗi sau khi chuẩn hóa id: hai lỗi 404 phải giống hệt nhau để không lộ sự tồn tại của dữ liệu người khác. */
    private static String shape(ResponseEntity<Map<String, Object>> response) {
        return response.getStatusCode().value() + "|" + errorCode(response) + "|"
                + message(response).replaceAll("\\d+", "#");
    }

    private void assertUntouched() {
        assertThat(requestFootprint(assignment, staffA)).isEqualTo(before);
        assertThat(requestFootprint(colleagueAssignment, colleagueA)).isEqualTo(colleagueBefore);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
        assertThat(fullRequestRow(colleagueRequest)).containsEntry("status", "PENDING");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
