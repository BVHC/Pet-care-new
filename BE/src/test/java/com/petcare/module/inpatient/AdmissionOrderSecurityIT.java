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
 * Xác thực và phân quyền của inpatient-v1 #5–#8 (docs/adr/0149 mục 29) trên HTTP thật: 401 (không token, phiên thu hồi,
 * tài khoản khóa / vô hiệu hóa), 400 {@code BR-TK-17}, vai trò theo hợp đồng ({@code @PreAuthorize} là hàng rào duy nhất
 * vì ADMIN / SUPER_MANAGER đi qua {@code BranchScope}), chi nhánh khác 403, người phụ trách Visit (ra và hủy y lệnh; gán
 * lại chuyển quyền), A08 chỉ làm việc không phải thuốc, nhân viên vừa điều chuyển / đổi chức vụ với cùng token. Mọi lời
 * gọi bị từ chối khẳng định DB không đổi. Era <b>năm 2102</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionOrderSecurityIT.TestBeans.class})
class AdmissionOrderSecurityIT extends AdmissionOrderItBase {

    private static final Instant NOW = Instant.parse("2102-03-14T02:00:00Z");

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
    private Admitted a;
    private long feedOrder;
    private long medOrder;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        otherBranch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        long service = enabledInpatientService(branch, 200_000L);
        String vetToken = token(vet);
        a = admitFromVisit(branch, customer, service, vet, vetToken);
        feedOrder = newFeeding(vetToken, a.id());
        medOrder = newMedication(vetToken, a.id(), data.clinicPrescriptionItem(a.visit(), vet));
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    /** Dấu vết ghi toàn bộ của ca: số y lệnh + trạng thái / cột đổi được của hai y lệnh có sẵn. */
    private String state() {
        return orderRows(a.id()) + "/" + orderFootprint(feedOrder) + "/" + orderFootprint(medOrder);
    }

    /** Bốn endpoint với body hợp lệ; trả trạng thái HTTP của từng cái theo thứ tự #5, #6, #7 (không thuốc), #8. */
    private List<ResponseEntity<Map<String, Object>>> fourCalls(String token) {
        return List.of(createOrder(token, a.id(), feeding("Thêm")), get(ordersPath(a.id()), token),
                complete(token, feedOrder, "Xong"), cancel(token, feedOrder, "Hủy"));
    }

    private void assertAllFour(String token, int status, String errorCode) {
        String before = state();
        List<ResponseEntity<Map<String, Object>>> calls = fourCalls(token);
        for (int i = 0; i < calls.size(); i++) {
            assertStatus(calls.get(i), status, errorCode);
        }
        assertThat(state()).as("DB không đổi").isEqualTo(before);
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenEveryEndpointIs401AndNothingIsWritten() {
        for (String token : new String[] {null, "garbage"}) {
            assertAllFour(token, 401, "UNAUTHENTICATED");
        }
    }

    @Test
    void aRevokedSessionOrALockedOrDisabledAccountIs401() {
        String token = token(vet);
        assertThat(get(ordersPath(a.id()), token).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vet);
        assertAllFour(token, 401, "UNAUTHENTICATED");

        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertAllFour(locked, 401, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", vet);
        assertAllFour(locked, 401, "UNAUTHENTICATED");
    }

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17OnAllFour() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        String token = token(vet);
        String before = state();

        assertRule(call(HttpMethod.POST, ordersPath(a.id()), token, "{}"), "BR-TK-17");
        assertRule(get(ordersPath(a.id()), token), "BR-TK-17");
        assertRule(call(HttpMethod.POST, completePath(feedOrder), token, "{}"), "BR-TK-17");
        assertRule(call(HttpMethod.POST, cancelPath(feedOrder), token, "{}"), "BR-TK-17");

        assertThat(state()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void adminSuperManagerBranchManagerAndCustomerAreRefusedOnAllFour() {
        for (long account : List.of(data.staff("ADMIN", null), data.staff("SUPER_MANAGER", null),
                data.staff("BRANCH_MANAGER", branch), customerAccount())) {
            assertAllFour(token(account), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void aReceptionistMayOnlyRead() {
        String token = token(data.staff("RECEPTIONIST", branch));
        String before = state();

        assertThat(get(ordersPath(a.id()), token).getStatusCode().value()).isEqualTo(200);
        assertStatus(createOrder(token, a.id(), feeding("Thêm")), 403, "ACCESS_DENIED");
        assertStatus(complete(token, feedOrder, "Xong"), 403, "ACCESS_DENIED");
        assertStatus(cancel(token, feedOrder, "Hủy"), 403, "ACCESS_DENIED");

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aCaretakerReadsAndCompletesOnlyNonMedicationOrders() {
        long caretaker = data.staff("CARETAKER", branch);
        String token = token(caretaker);
        String before = state();

        assertThat(get(ordersPath(a.id()), token).getStatusCode().value()).isEqualTo(200);
        assertStatus(createOrder(token, a.id(), feeding("Thêm")), 403, "ACCESS_DENIED");
        assertStatus(cancel(token, feedOrder, "Hủy"), 403, "ACCESS_DENIED");
        assertStatus(complete(token, medOrder, "Đã tiêm"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(state()).isEqualTo(before);

        ResponseEntity<Map<String, Object>> done = complete(token, feedOrder, "Đã cho ăn");
        assertThat(done.getStatusCode().value()).as(String.valueOf(done.getBody())).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT done_by FROM admission_orders WHERE id = ?", Long.class, feedOrder))
                .isEqualTo(caretaker);
        assertThat(orderStatus(medOrder)).isEqualTo("ORDERED");
    }

    @Test
    void aCaretakerGets403NotTheConflictOnAFinishedMedicationOrder() {
        assertThat(complete(token(vet), medOrder, "Đã tiêm").getStatusCode().value()).isEqualTo(200);

        assertStatus(complete(token(data.staff("CARETAKER", branch)), medOrder, "Lại"), 403,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    // ---------------------------------------------------------------- người phụ trách (BR-LT-15, BR-TN-08)

    @Test
    void onlyTheCurrentAssigneeMayOrderAndCancelButAnyVetOfTheBranchMayComplete() {
        long colleague = data.staff("VET", branch);
        String token = token(colleague);
        String before = state();

        assertStatus(createOrder(token, a.id(), feeding("Thêm")), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(cancel(token, feedOrder, "Hủy"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(get(ordersPath(a.id()), token).getStatusCode().value()).isEqualTo(200);
        assertThat(state()).isEqualTo(before);

        assertThat(complete(token, medOrder, "Đã tiêm").getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT done_by FROM admission_orders WHERE id = ?", Long.class, medOrder))
                .isEqualTo(colleague);
    }

    @Test
    void reassigningTheVisitMovesTheRightToOrderAndCancel() {
        long colleague = data.staff("VET", branch);
        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", colleague, a.visit());

        assertStatus(createOrder(token(vet), a.id(), feeding("Thêm")), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertStatus(cancel(token(vet), feedOrder, "Hủy"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");

        ResponseEntity<Map<String, Object>> created = createOrder(token(colleague), a.id(), feeding("Thêm"));
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        assertThat(orderCreatedBy(idOf(dataOf(created), "admissionOrderId"))).isEqualTo(colleague);
        assertThat(cancel(token(colleague), feedOrder, "Hủy").getStatusCode().value()).isEqualTo(200);
        // người phụ trách cũ vẫn thực hiện được (mọi VET của chi nhánh)
        assertThat(complete(token(vet), medOrder, "Đã tiêm").getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void staffOfAnotherBranchAreRefusedOnAllFour() {
        for (String role : new String[] {"VET", "CARETAKER", "RECEPTIONIST"}) {
            String token = token(data.staff(role, otherBranch));
            String before = state();

            assertStatus(get(ordersPath(a.id()), token), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
            if (role.equals("VET")) {
                assertStatus(createOrder(token, a.id(), feeding("Thêm")), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
                assertStatus(cancel(token, feedOrder, "Hủy"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
            }
            if (!role.equals("RECEPTIONIST")) {
                assertStatus(complete(token, feedOrder, "Xong"), 403, "ACCESS_DENIED_SCOPE_MISMATCH");
            }
            assertThat(state()).as(role).isEqualTo(before);
        }
    }

    @Test
    void aVetJustTransferredSeesOnlyTheNewBranchWithTheSameToken() {
        String token = token(vet);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, vet);

        assertAllFour(token, 403, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void aVetDemotedToReceptionistLosesTheRightToWriteWithTheSameToken() {
        String token = token(vet);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", vet);
        String before = state();

        assertStatus(createOrder(token, a.id(), feeding("Thêm")), 403, "ACCESS_DENIED");
        assertStatus(complete(token, feedOrder, "Xong"), 403, "ACCESS_DENIED");
        assertStatus(cancel(token, feedOrder, "Hủy"), 403, "ACCESS_DENIED");
        assertThat(get(ordersPath(a.id()), token).getStatusCode().value()).isEqualTo(200);
        assertThat(state()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- Bean Validation trước @PreAuthorize

    @Test
    void aCustomerWithABadBodyGets400BecauseValidationRunsBeforeTheRoleCheck() {
        String token = token(customerAccount());

        assertStatus(call(HttpMethod.POST, ordersPath(a.id()), token, "{'content':'x'}"), 400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, ordersPath(a.id()), token, "{not json"), 400, "MALFORMED_REQUEST");
        assertStatus(call(HttpMethod.POST, completePath(feedOrder), token, "{}"), 400, "VALIDATION_FAILED");
        assertStatus(call(HttpMethod.POST, cancelPath(feedOrder), token, "{}"), 400, "VALIDATION_FAILED");
        assertStatus(createOrder(token, a.id(), feeding("x")), 403, "ACCESS_DENIED");
    }
}
