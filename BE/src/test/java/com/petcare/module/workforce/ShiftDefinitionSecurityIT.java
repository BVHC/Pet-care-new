package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * Xác thực, phân quyền theo vai trò, phạm vi chi nhánh và độ mới của principal cho ca làm việc (workforce-v1 #1–3;
 * docs/adr/0149, plan D9). Era <b>năm 2046</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftDefinitionSecurityIT.TestBeans.class})
class ShiftDefinitionSecurityIT extends ShiftDefinitionItSupport {

    private static final Instant NOW = Instant.parse("2046-03-14T01:00:00Z");
    private static final String CREATE_BODY = "{'name':'Ca bảo mật','startTime':'08:00','endTime':'12:00'}";

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
    private long existingA;
    private String existingAName;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        existingA = definition(branchA, "Ca có sẵn", "08:00", "12:00", true);
        definition(branchA, "Ca ngừng", "13:00", "17:00", false);
        definition(branchB, "Ca của B", "08:00", "12:00", true);
        existingAName = (String) row(existingA).get("name");
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenEveryEndpointIs401AndNothingChanges() {
        String xmin = xmin(existingA);
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH}) {
                String path = method == HttpMethod.PATCH ? PATH + "/" + existingA : PATH;
                ResponseEntity<Map<String, Object>> response = call(method, path, token,
                        method == HttpMethod.GET ? null : CREATE_BODY);
                assertThat(response.getStatusCode().value()).as("%s %s token=%s", method, path, token).isEqualTo(401);
                assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
            }
        }
        assertThat(xmin(existingA)).isEqualTo(xmin);
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    @Test
    void aRevokedSessionIs401() {
        String token = token(managerA);
        assertThat(call(HttpMethod.GET, PATH, token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", managerA);

        assertThat(call(HttpMethod.GET, PATH, token).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, PATH, token, CREATE_BODY).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aLockedOrDisabledAccountIs401ImmediatelyEvenWithAValidToken() {
        String token = token(managerA);

        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", managerA);
        assertThat(call(HttpMethod.GET, PATH, token).getStatusCode().value()).isEqualTo(401);

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", managerA);
        assertThat(call(HttpMethod.POST, PATH, token, CREATE_BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeValidationOnAllThreeEndpoints() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", managerA);
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, PATH, token);
        ResponseEntity<Map<String, Object>> create = call(HttpMethod.POST, PATH, token, "{}");
        ResponseEntity<Map<String, Object>> update = call(HttpMethod.PATCH, PATH + "/" + existingA, token, "{}");

        for (ResponseEntity<Map<String, Object>> response : List.of(list, create, update)) {
            assertBusinessRule(response, "BR-TK-17");
        }
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void roleMatrixForTheThreeEndpoints() {
        long receptionist = data.staff("RECEPTIONIST", branchA);
        long vet = data.staff("VET", branchA);
        long caretaker = data.staff("CARETAKER", branchA);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        String xmin = xmin(existingA);

        for (long denied : new long[] {admin, superManager, customer}) {
            String token = token(denied);
            assertDenied(call(HttpMethod.GET, PATH, token));
            assertDenied(call(HttpMethod.POST, PATH, token, CREATE_BODY));
            assertDenied(call(HttpMethod.PATCH, PATH + "/" + existingA, token, "{'name':'Đổi'}"));
        }
        for (long staff : new long[] {receptionist, vet, caretaker}) {
            String token = token(staff);
            ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, PATH, token);
            assertThat(list.getStatusCode().value()).isEqualTo(200);
            assertThat(listOf(list)).extracting(m -> m.get("active")).containsOnly(true);
            assertDenied(call(HttpMethod.POST, PATH, token, CREATE_BODY));
            assertDenied(call(HttpMethod.PATCH, PATH + "/" + existingA, token, "{'name':'Đổi'}"));
        }
        assertThat(xmin(existingA)).isEqualTo(xmin);
        assertThat(definitionCount(branchA)).isEqualTo(2);
        assertThat(auditCountForBranch(branchA)).isZero();
    }

    @Test
    void aStaffMemberWithAValidBodyGets403ButAnInvalidBodyGets400FirstBecauseValidationRunsBeforeTheRoleCheck() {
        String token = token(data.staff("RECEPTIONIST", branchA));

        assertDenied(call(HttpMethod.POST, PATH, token, CREATE_BODY));
        ResponseEntity<Map<String, Object>> invalid = call(HttpMethod.POST, PATH, token, "{}");

        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(invalid)).isEqualTo("VALIDATION_FAILED");
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void aManagerOnlySeesAndCreatesInOwnBranchWhateverTheRequestSays() {
        String tokenB = token(managerB);

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, PATH + "?branchId=" + branchA, tokenB);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(listOf(list)).extracting(m -> ((Number) m.get("branchId")).longValue()).containsOnly(branchB);

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, PATH, tokenB,
                "{'name':'Ca của B','startTime':'08:00','endTime':'12:00','branchId':" + branchA + "}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(dataOf(created)).containsEntry("branchId", (int) branchB);
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    @Test
    void aManagerCannotChangeAShiftOfAnotherBranchEvenWithAValidBody() {
        String xmin = xmin(existingA);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH + "/" + existingA,
                token(managerB), "{'name':'Chiếm quyền'}");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(xmin(existingA)).isEqualTo(xmin);
        assertThat(row(existingA)).containsEntry("name", existingAName);
        assertThat(auditCount(existingA)).isZero();
    }

    // ---------------------------------------------------------------- độ mới của principal

    @Test
    void aTransferTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(managerA);
        assertThat(ids(call(HttpMethod.GET, PATH, token))).contains(existingA);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, managerA);

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET, PATH, token);
        assertThat(listOf(list)).extracting(m -> ((Number) m.get("branchId")).longValue()).containsOnly(branchB);
        ResponseEntity<Map<String, Object>> oldBranch = call(HttpMethod.PATCH, PATH + "/" + existingA, token,
                "{'name':'Còn quyền?'}");
        assertThat(oldBranch.getStatusCode().value()).isEqualTo(403);
        assertThat(row(existingA)).containsEntry("name", existingAName);
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(managerA);
        assertThat(call(HttpMethod.GET, PATH, token).getBody().toString()).contains("Ca ngừng");

        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", managerA);

        assertThat(listOf(call(HttpMethod.GET, PATH, token))).extracting(m -> m.get("active")).containsOnly(true);
        assertDenied(call(HttpMethod.POST, PATH, token, CREATE_BODY));
        assertThat(definitionCount(branchA)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- audit người thực hiện

    @Test
    void theAuditActorIsTheAuthenticatedManagerAndDeniedCallsAreNotAudited() {
        long created = ((Number) dataOf(call(HttpMethod.POST, PATH, token(managerA), CREATE_BODY))
                .get("shiftDefinitionId")).longValue();
        assertDenied(call(HttpMethod.POST, PATH, token(data.staff("VET", branchA)), CREATE_BODY));

        assertThat(jdbc.queryForObject("""
                SELECT actor_account_id FROM audit_logs WHERE entity_type = 'shift_definitions' AND entity_id = ?""",
                Long.class, created)).isEqualTo(managerA);
        assertThat(auditCountForBranch(branchA)).isEqualTo(1);
    }

    private static void assertDenied(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return listOf(response).stream().map(m -> ((Number) m.get("shiftDefinitionId")).longValue()).toList();
    }
}
