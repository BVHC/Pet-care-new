package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
 * Xác thực và phân quyền của hủy phân ca (workforce-v1 #6; docs/adr/0149 bước 4), khuôn
 * {@code ShiftAssignmentSecurityIT}: 401 trước mọi xử lý, BR-TK-17 trước validation, validation trước vai trò, chỉ
 * BRANCH_MANAGER, phạm vi theo <b>chi nhánh của phân ca</b> (không phải chi nhánh hiện tại của nhân viên), không khóa
 * dòng tài khoản của chi nhánh khác, vai trò và chi nhánh đọc lại từ DB ở request kế tiếp. Mọi lệnh bị từ chối không để
 * lại dấu vết (phân ca, yêu cầu, audit, outbox). Era <b>năm 2054</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentCancelSecurityIT.TestBeans.class})
class ShiftAssignmentCancelSecurityIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2054-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2054, 3, 21);
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
    private long managerB;
    private long staffA;
    private long shiftA;
    private long assignment;
    private long request;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        staffA = data.staff("RECEPTIONIST", branchA);
        shiftA = definition(branchA, "Ca A", "08:00", "12:00", true);
        assignment = data.assignment(branchA, staffA, shiftA, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staffA);
        before = footprint(assignment, staffA);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            purgeCancelOutbox(staffA);
            finish();
        }
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenTheCancelIs401AndLeavesNoTrace() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            ResponseEntity<Map<String, Object>> response = cancel(token, "Nghỉ lễ");

            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
        assertUntouched();
    }

    @Test
    void aRevokedSessionIs401() {
        String token = token(managerA);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", managerA);

        assertThat(cancel(token, "Nghỉ lễ").getStatusCode().value()).isEqualTo(401);
        assertUntouched();
    }

    @Test
    void aLockedOrDisabledManagerIs401ImmediatelyEvenWithAValidToken() {
        String token = token(managerA);

        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", managerA);
        assertThat(cancel(token, "Nghỉ lễ").getStatusCode().value()).isEqualTo(401);

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", managerA);
        assertThat(cancel(token, "Nghỉ lễ").getStatusCode().value()).isEqualTo(401);
        assertUntouched();
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeValidation() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerA);
        String token = token(managerA);

        assertBusinessRule(call(HttpMethod.POST, cancelPath(assignment), token, "{}"), "BR-TK-17");
        assertBusinessRule(cancel(token, "Nghỉ lễ"), "BR-TK-17");
        assertUntouched();
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyABranchManagerMayCancelAndTheOwnerOfTheAssignmentMayNot() {
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();

        for (long denied : new long[] {staffA, vet, caretaker, admin, superManager, customer}) {
            assertDenied(cancel(token(denied), "Nghỉ lễ"));
        }

        assertUntouched();
    }

    @Test
    void validationRunsBeforeTheRoleCheckSoAnInvalidBodyIs400AndAValidOneIs403() {
        String token = token(staffA);

        assertDenied(cancel(token, "Nghỉ lễ"));
        ResponseEntity<Map<String, Object>> invalid = call(HttpMethod.POST, cancelPath(assignment), token, "{}");

        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(invalid)).isEqualTo("VALIDATION_FAILED");
        assertUntouched();
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void anotherBranchsManagerGets403ScopeMismatchForAnExistingAssignmentAnd404ForAnUnknownOne() {
        String token = token(managerB);

        ResponseEntity<Map<String, Object>> foreign = cancel(token, "Nghỉ lễ");
        ResponseEntity<Map<String, Object>> unknown = call(HttpMethod.POST, cancelPath(Long.MAX_VALUE - 1), token,
                reasonBody("Nghỉ lễ"));

        assertThat(foreign.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(foreign)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertUntouched();
    }

    @Test
    void aForeignAssignmentNeverLocksTheForeignStaffAccount() throws Exception {
        // Một transaction khác giữ khóa độc quyền trên dòng tài khoản của nhân viên chi nhánh A. Nếu request của quản lý
        // B lỡ khóa dòng đó trước khi phân quyền thì sẽ treo; vì kiểm phạm vi trước khi khóa nên phải trả 403 ngay.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, staffA);
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        try {
            Future<ResponseEntity<Map<String, Object>>> foreign = pool.submit(() -> cancel(token(managerB), "Nghỉ lễ"));

            assertThat(foreign.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(403);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        assertUntouched();
    }

    @Test
    void theScopeIsTheBranchOfTheAssignmentNotTheBranchTheStaffMemberIsInNow() {
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, staffA);

        ResponseEntity<Map<String, Object>> newBranchManager = cancel(token(managerB), "Nghỉ lễ");
        assertThat(newBranchManager.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(newBranchManager)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertUntouched();

        ResponseEntity<Map<String, Object>> owningBranchManager = cancel(token(managerA), "Nghỉ lễ");
        assertThat(owningBranchManager.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
    }

    @Test
    void aTransferTakesEffectOnTheNextRequestWithTheSameToken() {
        long second = data.assignment(branchA, staffA, shiftA, DAY.plusDays(1), "SCHEDULED", "MANAGER", null);
        String token = token(managerA);
        assertThat(cancel(token, "Nghỉ lễ").getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, managerA);

        ResponseEntity<Map<String, Object>> afterTransfer = call(HttpMethod.POST, cancelPath(second), token,
                reasonBody("Nghỉ lễ"));
        assertThat(afterTransfer.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(afterTransfer)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(assignmentRow(second)).containsEntry("status", "SCHEDULED");
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(managerA);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", managerA);

        assertDenied(cancel(token, "Nghỉ lễ"));
        assertUntouched();
    }

    // ---------------------------------------------------------------- người thực hiện

    @Test
    void theActorOfTheAssignmentAndOfTheAuditIsTheAuthenticatedManagerNotAnythingInTheBody() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, cancelPath(assignment), token(managerA),
                "{'reason':'Nghỉ lễ','statusChangedBy':" + managerB + ",'decidedBy':" + managerB + "}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status_changed_by", managerA);
        assertThat(jdbc.queryForObject("""
                SELECT actor_account_id FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND entity_id = ? AND action = 'SHIFT_ASSIGNMENT_CANCELLED'""",
                Long.class, assignment)).isEqualTo(managerA);
        assertThat(requestRow(request)).containsEntry("decided_by", null);
        assertThat(cancelOutbox(staffA)).extracting(row -> row.get("recipient_account_id"))
                .as("thông báo tới nhân viên của phân ca, không phải quản lý").doesNotContain(managerA, managerB);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> cancel(String token, String reason) {
        return call(HttpMethod.POST, cancelPath(assignment), token, reasonBody(reason));
    }

    /** Phân ca, yêu cầu chờ, audit và outbox vẫn y như lúc dựng dữ liệu. */
    private void assertUntouched() {
        assertThat(footprint(assignment, staffA)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING");
    }

    private static void assertDenied(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
