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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xác thực và phân quyền của inpatient-v1 #1–#3 (docs/adr/0149 mục 20) trên HTTP thật: 401 (không token, phiên thu hồi,
 * tài khoản khóa), 400 {@code BR-TK-17}, vai trò theo hợp đồng (ADMIN / SUPER_MANAGER đi qua {@code BranchScope} nên
 * {@code @PreAuthorize} là hàng rào duy nhất), chi nhánh khác 403, người phụ trách lượt (gán lại chuyển quyền), chuồng
 * chi nhánh khác, nhân viên vừa điều chuyển, và Bean Validation chạy trước {@code @PreAuthorize}. Era <b>năm 2096</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionSecurityIT.TestBeans.class})
class AdmissionSecurityIT extends AdmissionItBase {

    private static final Instant NOW = Instant.parse("2096-03-14T02:00:00Z");

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
    private long customer;
    private long service;
    private long pet;
    private long visit;
    private long kennel;
    private long admission;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        otherBranch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, 200_000L);
        pet = data.pet(customer);
        visit = data.visit(branch, customer, pet, vet);
        kennel = data.kennel(branch);
        // Một ca có sẵn (chèn thẳng) cho #2 / #3.
        long seededPet = data.pet(customer);
        long seededVisit = data.visit(branch, customer, seededPet, vet);
        admission = jdbc.queryForObject("""
                INSERT INTO admissions (code, visit_id, pet_id, branch_id, kennel_id, service_id, unit_price, status,
                        admitted_by, admitted_at)
                VALUES ('NT-9603-90001', ?, ?, ?, ?, ?, 200000, 'ADMITTED', ?, ?) RETURNING id""", Long.class,
                seededVisit, seededPet, branch, data.kennel(branch), service, vet, java.sql.Timestamp.from(NOW));
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    private String detail() {
        return ADMISSIONS + "/" + admission;
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenEveryEndpointIs401AndNothingIsWritten() {
        for (String token : new String[] {null, "garbage"}) {
            assertStatus(admit(token, fromVisit(visit, service, kennel)), 401, "UNAUTHENTICATED");
            assertStatus(get(ADMISSIONS, token), 401, "UNAUTHENTICATED");
            assertStatus(get(detail(), token), 401, "UNAUTHENTICATED");
        }
        assertThat(admissionCount(pet)).isZero();
    }

    @Test
    void aRevokedSessionOrALockedOrDisabledAccountIs401() {
        String token = token(vet);
        assertThat(get(ADMISSIONS, token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vet);
        assertStatus(admit(token, fromVisit(visit, service, kennel)), 401, "UNAUTHENTICATED");

        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertStatus(get(ADMISSIONS, locked), 401, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", vet);
        assertStatus(admit(locked, fromVisit(visit, service, kennel)), 401, "UNAUTHENTICATED");
        assertThat(admissionCount(pet)).isZero();
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17OnAllThree() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        String token = token(vet);

        assertRule(admit(token, "{}"), "BR-TK-17");
        assertRule(get(ADMISSIONS, token), "BR-TK-17");
        assertRule(get(detail(), token), "BR-TK-17");
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyAVetMayAdmit() {
        List<Long> denied = List.of(data.staff("RECEPTIONIST", branch), data.staff("CARETAKER", branch),
                data.staff("BRANCH_MANAGER", branch), data.staff("SUPER_MANAGER", null), data.staff("ADMIN", null),
                customerAccount());

        for (long account : denied) {
            assertStatus(admit(token(account), fromVisit(visit, service, kennel)), 403, "ACCESS_DENIED");
        }
        assertThat(admissionCount(pet)).isZero();
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void receptionistVetAndCaretakerOfTheBranchMayReadButNoOneElse() {
        for (long reader : new long[] {data.staff("RECEPTIONIST", branch), vet, data.staff("CARETAKER", branch)}) {
            String token = token(reader);
            ResponseEntity<Map<String, Object>> list = get(ADMISSIONS, token);
            assertThat(list.getStatusCode().value()).isEqualTo(200);
            assertThat(contentOf(list)).extracting(r -> idOf(r, "admissionId")).containsExactly(admission);
            assertThat(get(detail(), token).getStatusCode().value()).isEqualTo(200);
        }
        for (long other : new long[] {data.staff("BRANCH_MANAGER", branch), data.staff("SUPER_MANAGER", null),
                data.staff("ADMIN", null), customerAccount()}) {
            String token = token(other);
            assertStatus(get(ADMISSIONS, token), 403, "ACCESS_DENIED");
            assertStatus(get(detail(), token), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void aCustomerWithABadBodyGets400BecauseValidationRunsBeforeTheRoleCheck() {
        String token = token(customerAccount());

        assertStatus(admit(token, "{'visitId':1,'kennelId':1}"), 400, "VALIDATION_FAILED");
        assertStatus(admit(token, "{not json"), 400, "MALFORMED_REQUEST");
        assertStatus(admit(token, fromVisit(visit, service, kennel)), 403, "ACCESS_DENIED");
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void aVetOfAnotherBranchGets403OnTheVisitTheBookingAndTheAdmissionAndSeesOnlyTheirOwnBranch() {
        String token = token(data.staff("VET", otherBranch));
        long booking = data.checkedInBooking(branch, customer, data.pet(customer), vet);

        assertStatus(admit(token, fromVisit(visit, service, kennel)), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(admit(token, fromBooking(booking, service)), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(get(detail(), token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(contentOf(get(ADMISSIONS, token))).isEmpty();
        assertThat(admissionCount(pet)).isZero();
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void aKennelOfAnotherBranchIsRefusedEvenToTheAssignedVet() {
        long foreign = data.kennel(otherBranch);

        assertRule(admit(token(vet), fromVisit(visit, service, foreign)), "BR-LT-13");

        assertThat(kennelStatus(foreign)).isEqualTo("AVAILABLE");
        assertThat(admissionCount(pet)).isZero();
    }

    // ---------------------------------------------------------------- người phụ trách (BR-LT-13a, BR-TN-08)

    @Test
    void onlyTheCurrentAssigneeMayAdmitFromAVisitAndReassigningMovesThatRight() {
        long colleague = data.staff("VET", branch);

        assertRule(admit(token(colleague), fromVisit(visit, service, kennel)), "BR-LT-13");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");

        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", colleague, visit);
        assertRule(admit(token(vet), fromVisit(visit, service, kennel)), "BR-LT-13");
        assertThat(admit(token(colleague), fromVisit(visit, service, kennel)).getStatusCode().value())
                .isEqualTo(201);
    }

    @Test
    void anyVetOfTheBranchMayAdmitFromABookingAndBecomesTheTreatmentAssignee() {
        long colleague = data.staff("VET", branch);
        long boarder = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, boarder, vet);

        ResponseEntity<Map<String, Object>> response = admit(token(colleague), fromBooking(booking, service));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class,
                idOf(dataOf(response), "visitId"))).isEqualTo(colleague);
    }

    @Test
    void aVetJustTransferredSeesOnlyTheNewBranchWithTheSameToken() {
        String token = token(vet);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, vet);

        assertStatus(admit(token, fromVisit(visit, service, kennel)), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(get(detail(), token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(contentOf(get(ADMISSIONS, token))).isEmpty();
    }

    @Test
    void aVetDemotedToReceptionistLosesTheRightToAdmitWithTheSameToken() {
        String token = token(vet);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", vet);

        assertStatus(admit(token, fromVisit(visit, service, kennel)), 403, "ACCESS_DENIED");
        assertThat(get(ADMISSIONS, token).getStatusCode().value()).isEqualTo(200);
    }
}
