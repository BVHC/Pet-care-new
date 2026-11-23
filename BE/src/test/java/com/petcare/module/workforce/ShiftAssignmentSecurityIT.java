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
 * Xác thực, phân quyền theo vai trò, phạm vi chi nhánh, độ mới của principal và audit actor cho phân ca (workforce-v1
 * #4–5; docs/adr/0149 bước 3). Khuôn {@code ShiftDefinitionSecurityIT}. Khác biệt có chủ đích: ở đây A06–A08 cũng bị
 * 403 trên cả hai endpoint (chỉ BRANCH_MANAGER), và id <b>trong body</b> của chi nhánh khác trả 404 (không phải 403) để
 * không lộ sự tồn tại. Era <b>năm 2049</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentSecurityIT.TestBeans.class})
class ShiftAssignmentSecurityIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2049-03-14T01:00:00Z");
    private static final String DAY = "2049-03-21";
    private static final String LIST = ASSIGNMENTS + "?from=2049-03-14&to=2049-03-28";
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
    private long staffB;
    private long shiftA;
    private long shiftB;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        staffA = data.staff("RECEPTIONIST", branchA);
        staffB = data.staff("VET", branchB);
        shiftA = definition(branchA, "Ca A", "08:00", "12:00", true);
        shiftB = definition(branchB, "Ca B", "08:00", "12:00", true);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenBothEndpointsAre401AndNothingIsWritten() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, LIST, token);
            ResponseEntity<Map<String, Object>> create = call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY));
            for (ResponseEntity<Map<String, Object>> response : List.of(list, create)) {
                assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
                assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
            }
        }
        assertThat(assignmentCount(branchA)).isZero();
    }

    @Test
    void aRevokedSessionIs401() {
        String token = token(managerA);
        assertThat(call(HttpMethod.GET, LIST, token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", managerA);

        assertThat(call(HttpMethod.GET, LIST, token).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)).getStatusCode().value())
                .isEqualTo(401);
        assertThat(assignmentCount(branchA)).isZero();
    }

    @Test
    void aLockedOrDisabledManagerIs401ImmediatelyEvenWithAValidToken() {
        String token = token(managerA);

        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", managerA);
        assertThat(call(HttpMethod.GET, LIST, token).getStatusCode().value()).isEqualTo(401);

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", managerA);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)).getStatusCode().value())
                .isEqualTo(401);
        assertThat(assignmentCount(branchA)).isZero();
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeValidationOnBothEndpoints() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerA);
        String token = token(managerA);

        assertBusinessRule(call(HttpMethod.GET, LIST, token), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.POST, ASSIGNMENTS, token, "{}"), "BR-TK-17");
        assertThat(assignmentCount(branchA)).isZero();
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyABranchManagerMayUseEitherEndpoint() {
        long receptionist = data.staff("RECEPTIONIST", branchA);
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();

        for (long denied : new long[] {receptionist, vet, caretaker, admin, superManager, customer}) {
            String token = token(denied);
            assertDenied(call(HttpMethod.GET, LIST, token));
            assertDenied(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)));
        }

        assertThat(assignmentCount(branchA)).isZero();
        assertThat(assignmentAuditCountOfStaff(staffA)).isZero();
    }

    @Test
    void validationRunsBeforeTheRoleCheckSoAnInvalidBodyIs400AndAValidOneIs403() {
        String token = token(data.staff("RECEPTIONIST", branchA));

        assertDenied(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)));
        ResponseEntity<Map<String, Object>> invalid = call(HttpMethod.POST, ASSIGNMENTS, token, "{}");

        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(invalid)).isEqualTo("VALIDATION_FAILED");
        assertThat(assignmentCount(branchA)).isZero();
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void aManagerOnlyListsOwnBranchWhateverTheQueryAsks() {
        long inA = scheduled(branchA, staffA, shiftA, LocalDate.of(2049, 3, 21));
        long inB = scheduled(branchB, staffB, shiftB, LocalDate.of(2049, 3, 21));

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, LIST + "&branchId=" + branchA, token(managerB));

        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(list))).extracting(m -> idOf(m, "shiftAssignmentId")).containsExactly(inB)
                .doesNotContain(inA);
    }

    @Test
    void aStaffFilterOfAnotherBranchGivesAnEmptyPageNotAnError() {
        scheduled(branchA, staffA, shiftA, LocalDate.of(2049, 3, 21));

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, LIST + "&staffId=" + staffA, token(managerB));

        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(list))).isEmpty();
    }

    @Test
    void aForeignStaffIdAndAnUnknownStaffIdLookTheSameAndNothingIsWritten() {
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> foreign = call(HttpMethod.POST, ASSIGNMENTS, token, body(staffB, shiftA, DAY));
        ResponseEntity<Map<String, Object>> unknown = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(Long.MAX_VALUE - 1, shiftA, DAY));
        ResponseEntity<Map<String, Object>> notStaff = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(customerAccount(), shiftA, DAY));

        for (ResponseEntity<Map<String, Object>> response : List.of(foreign, unknown, notStaff)) {
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(sameShape(message(foreign), message(unknown))).as("cùng khung thông báo, chỉ khác id").isTrue();
        assertThat(assignmentCount(branchA)).isZero();
        assertThat(assignmentCount(branchB)).isZero();
    }

    @Test
    void aForeignShiftIs404AndTheForeignStaffAccountIsNeverLocked() throws Exception {
        // Một transaction khác giữ khóa độc quyền trên dòng tài khoản của nhân viên chi nhánh B. Nếu request của quản lý
        // A lỡ khóa dòng đó thì sẽ treo; vì chỉ kiểm không khóa rồi trả 404 nên phải trả về ngay.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, staffB);
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        try {
            Future<ResponseEntity<Map<String, Object>>> foreignStaff = pool.submit(
                    () -> call(HttpMethod.POST, ASSIGNMENTS, token(managerA), body(staffB, shiftA, DAY)));
            ResponseEntity<Map<String, Object>> response = foreignStaff.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        ResponseEntity<Map<String, Object>> foreignShift = call(HttpMethod.POST, ASSIGNMENTS, token(managerA),
                body(staffA, shiftB, DAY));
        assertThat(foreignShift.getStatusCode().value()).isEqualTo(404);
        assertThat(assignmentCount(branchA)).isZero();
    }

    @Test
    void aManagerCannotBeAssignedAndTheOwnBranchVetCanBe() {
        String token = token(managerA);
        long vetA = data.staff("VET", branchA);

        ResponseEntity<Map<String, Object>> self = call(HttpMethod.POST, ASSIGNMENTS, token, body(managerA, shiftA, DAY));
        ResponseEntity<Map<String, Object>> vet = call(HttpMethod.POST, ASSIGNMENTS, token, body(vetA, shiftA, DAY));

        assertBusinessRule(self, "BR-NS-02");
        assertThat(vet.getStatusCode().value()).isEqualTo(201);
        assertThat(assignmentCountOfStaff(managerA)).isZero();
        assertThat(assignmentCountOfStaff(vetA)).isEqualTo(1);
    }

    @Test
    void extraBodyFieldsCannotSetBranchSourceOrStatus() {
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, ASSIGNMENTS, token(managerA),
                "{'staffId':" + staffA + ",'shiftDefinitionId':" + shiftA + ",'workDate':'" + DAY + "','branchId':"
                        + branchB + ",'source':'SELF','status':'CANCELLED','reason':'x'}");

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> row = assignmentRow(idOf(dataOf(created), "shiftAssignmentId"));
        assertThat(row).containsEntry("branch_id", branchA).containsEntry("source", "MANAGER")
                .containsEntry("status", "SCHEDULED");
        assertThat(row.get("reason")).isNull();
        assertThat(assignmentCount(branchB)).isZero();
    }

    // ---------------------------------------------------------------- độ mới của principal

    @Test
    void aTransferTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(managerA);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)).getStatusCode().value())
                .isEqualTo(201);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, managerA);

        ResponseEntity<Map<String, Object>> oldStaff = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(data.staff("VET", branchA), shiftA, DAY));
        assertThat(oldStaff.getStatusCode().value()).isEqualTo(404);
        assertThat(contentOf(dataOf(call(HttpMethod.GET, LIST, token)))).extracting(m -> idOf(m, "branchId"))
                .doesNotContain(branchA);
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(managerA);
        assertThat(call(HttpMethod.GET, LIST, token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", managerA);

        assertDenied(call(HttpMethod.GET, LIST, token));
        assertDenied(call(HttpMethod.POST, ASSIGNMENTS, token, body(staffA, shiftA, DAY)));
        assertThat(assignmentCount(branchA)).isZero();
    }

    // ---------------------------------------------------------------- audit người thực hiện

    @Test
    void theAuditActorIsTheAuthenticatedManagerAndRefusedCallsAreNotAudited() {
        long created = idOf(dataOf(call(HttpMethod.POST, ASSIGNMENTS, token(managerA), body(staffA, shiftA, DAY))),
                "shiftAssignmentId");
        assertDenied(call(HttpMethod.POST, ASSIGNMENTS, token(data.staff("VET", branchA)), body(staffA, shiftA, "2049-03-22")));
        call(HttpMethod.POST, ASSIGNMENTS, token(managerB), body(staffA, shiftA, "2049-03-23"));

        assertThat(jdbc.queryForObject("""
                SELECT actor_account_id FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND entity_id = ?""", Long.class, created)).isEqualTo(managerA);
        assertThat(assignmentAuditCountOfStaff(staffA)).isEqualTo(1);
    }

    private static void assertDenied(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
    }

    /** Hai thông báo cùng khung khi bỏ phần số id. */
    private static boolean sameShape(String first, String second) {
        return first.replaceAll("\\d+", "#").equals(second.replaceAll("\\d+", "#"));
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
