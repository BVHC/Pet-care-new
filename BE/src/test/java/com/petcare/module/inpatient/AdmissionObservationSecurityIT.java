package com.petcare.module.inpatient;

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
 * Xác thực và phân quyền của inpatient-v1 #9–#10 (docs/adr/0149 mục 31–36) trên HTTP thật: 401 (không token, phiên thu
 * hồi, tài khoản khóa / vô hiệu hóa), 400 {@code BR-TK-17} (trước validation), vai trò theo hợp đồng ({@code @PreAuthorize}
 * là hàng rào duy nhất vì ADMIN / SUPER_MANAGER đi qua {@code BranchScope}), chi nhánh khác 403, nhân viên vừa điều
 * chuyển / đổi chức vụ với cùng token, và <b>không lộ dữ liệu</b>: mọi lời gọi bị từ chối khẳng định DB và outbox không
 * đổi. Era <b>năm 2106</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionObservationSecurityIT.TestBeans.class})
class AdmissionObservationSecurityIT extends AdmissionObservationItBase {

    private static final Instant NOW = Instant.parse("2106-03-14T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long otherBranch;
    private long vet;
    private long caretaker;
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        otherBranch = data.branch();
        vet = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        long service = enabledInpatientService(branch, 200_000L);
        long[] online = data.onlineCustomer("Nguyễn Văn An", "ACTIVE");
        String vetToken = token(vet);
        a = admitFromVisit(branch, online[1], service, vet, vetToken);
        newObservation(vetToken, a.id(), body(false, null));
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    private String state() {
        return footprintOf(a);
    }

    private ResponseEntity<Map<String, Object>> post(String token) {
        // abnormal + cân nặng: nếu lọt qua thì sẽ ghi mục, dòng cân và outbox, nên mọi dấu vết đều đổi
        return record(token, a.id(), body(true, "5"));
    }

    private ResponseEntity<Map<String, Object>> getList(String token) {
        return listObservations(token, a.id(), "");
    }

    private void assertBoth(String token, int status, String errorCode) {
        String before = state();
        assertStatus(post(token), status, errorCode);
        assertStatus(getList(token), status, errorCode);
        assertThat(state()).as("DB và outbox không đổi").isEqualTo(before);
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenBothEndpointsAre401AndNothingIsWritten() {
        for (String token : new String[] {null, "garbage"}) {
            assertBoth(token, 401, "UNAUTHENTICATED");
        }
    }

    @Test
    void aRevokedSessionOrALockedOrDisabledAccountIs401() {
        String token = token(caretaker);
        assertThat(getList(token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", caretaker);
        assertBoth(token, 401, "UNAUTHENTICATED");

        String locked = token(caretaker);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", caretaker);
        assertBoth(locked, 401, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", caretaker);
        assertBoth(locked, 401, "UNAUTHENTICATED");
    }

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17BeforeAnyValidationOrRoleCheck() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", caretaker);
        String token = token(caretaker);
        String before = state();

        // body sai hình thức mà vẫn là BR-TK-17: interceptor chạy trước khi Spring đọc body
        assertRule(call(HttpMethod.POST, observationsPath(a.id()), token, "{}"), "BR-TK-17");
        assertRule(call(HttpMethod.POST, observationsPath(a.id()), token, "{'abnormal':"), "BR-TK-17");
        assertRule(post(token), "BR-TK-17");
        assertRule(getList(token), "BR-TK-17");

        assertThat(state()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void adminSuperManagerBranchManagerAndCustomerAreRefusedOnBothEndpoints() {
        for (long account : List.of(data.staff("ADMIN", null), data.staff("SUPER_MANAGER", null),
                data.staff("BRANCH_MANAGER", branch), customerAccount())) {
            assertBoth(token(account), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void aReceptionistMayReadButNotWrite() {
        String token = token(data.staff("RECEPTIONIST", branch));
        String before = state();

        assertStatus(post(token), 403, "ACCESS_DENIED");
        assertThat(getList(token).getStatusCode().value()).isEqualTo(200);

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void vetsAndCaretakersOfTheBranchMayWriteAndRead() {
        for (long staff : List.of(vet, caretaker, data.staff("VET", branch))) {
            String token = token(staff);
            assertThat(post(token).getStatusCode().value()).as("write " + staff).isEqualTo(201);
            assertThat(getList(token).getStatusCode().value()).as("read " + staff).isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- chi nhánh

    @Test
    void staffOfAnotherBranchAreRefusedWithAScopeMismatchOnBothEndpoints() {
        for (long account : List.of(data.staff("VET", otherBranch), data.staff("CARETAKER", otherBranch),
                data.staff("RECEPTIONIST", otherBranch))) {
            String token = token(account);
            String before = state();

            // vai trò đúng nhưng ca của chi nhánh khác: 403 phạm vi (RECEPTIONIST cũng không được ghi: 403 vai trò)
            ResponseEntity<Map<String, Object>> write = post(token);
            assertThat(write.getStatusCode().value()).isEqualTo(403);
            assertStatus(getList(token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");

            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    void aVetAndACaretakerOfAnotherBranchGetScopeMismatchNotACodeThatLeaksTheAdmission() {
        assertStatus(post(token(data.staff("VET", otherBranch))), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(post(token(data.staff("CARETAKER", otherBranch))), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo("1/0/0");
    }

    @Test
    void aStaffMemberTransferredToAnotherBranchLosesAccessWithTheSameToken() {
        String token = token(caretaker);
        assertThat(getList(token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, caretaker);

        String before = state();
        assertStatus(post(token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(getList(token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aStaffMemberWhoseRoleChangedLosesWriteAccessWithTheSameToken() {
        String token = token(caretaker);
        assertThat(post(token).getStatusCode().value()).isEqualTo(201);

        // vai trò đọc từ DB mỗi request, không từ JWT
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", caretaker);

        String before = state();
        assertStatus(post(token), 403, "ACCESS_DENIED");
        assertThat(getList(token).getStatusCode().value()).isEqualTo(200);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aMissingAdmissionIs404ForAuthorisedStaffAndRoleErrorsStillWinForOthers() {
        assertStatus(record(token(vet), 999_999_999L, body(false, null)), 404, "RESOURCE_NOT_FOUND");
        assertStatus(record(token(data.staff("BRANCH_MANAGER", branch)), 999_999_999L, body(false, null)), 403,
                "ACCESS_DENIED");
    }
}
