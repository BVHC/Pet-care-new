package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierItSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefundConfirmIT extends CashierItSupport {

    private long branch;
    private long receptionist;
    private long manager;
    private long customer;
    private long shift;
    private String staffToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        customer = newCustomer();
        shift = openShiftSql(branch, receptionist, false, LocalDate.now(), Instant.now().minusSeconds(60));
        staffToken = token(receptionist);
        managerToken = token(manager);
    }

    @AfterEach
    void deleteRefundFixtures() {
        jdbc.update("DELETE FROM refunds WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM return_requests WHERE branch_id = ?", branch);
    }

    @Test
    void confirmsEachCounterMethodOnceAndSubtractsItFromItsOwnShiftBucket() {
        paymentSql(branch, customer, shift, "CASH", 150_000, receptionist);
        paymentSql(branch, customer, shift, "TRANSFER", 75_000, receptionist);
        Fixture cash = fixture("CASH", "REQUESTED", 40_000);
        Fixture transfer = fixture("TRANSFER", "REQUESTED", 20_000);

        Map<String, Object> confirmed = data(confirm(cash.refund(), staffToken, Map.of()), HttpStatus.OK);
        assertThat(confirmed).containsEntry("status", "SUCCEEDED").containsEntry("method", "CASH");
        assertThat(number(confirmed.get("refundId"))).isEqualTo(cash.refund());
        assertThat(confirmed.get("completedAt")).isNotNull();
        data(confirm(transfer.refund(), staffToken, Map.of("note", "  Mã ngân hàng  ")), HttpStatus.OK);
        assertThat(confirm(cash.refund(), staffToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertSucceeded(cash, receptionist, shift);
        assertSucceeded(transfer, receptionist, shift);
        assertThat(auditReason(transfer.refund())).isEqualTo("Mã ngân hàng");

        Map<String, Object> closed = data(call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/close",
                staffToken, Map.of("countedCash", 110_000)), HttpStatus.OK);
        assertThat(closed).containsEntry("expectedCash", 110_000).containsEntry("expectedTransfer", 55_000);
    }

    @Test
    void requiresTheProperRoleBranchAndOwnOpenShift() {
        Fixture cash = fixture("CASH", "REQUESTED", 15_000);
        assertThat(confirm(cash.refund(), null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(confirm(cash.refund(), managerToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String otherBranch = token(receptionist(newBranch()));
        assertThat(confirm(cash.refund(), otherBranch, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String withoutShift = token(receptionist(branch));
        assertRule(confirm(cash.refund(), withoutShift, Map.of()), "BR-TG-01");
        assertThat(confirm(Long.MAX_VALUE, staffToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refundStatus(cash.refund())).isEqualTo("REQUESTED");
        assertThat(returnStatus(cash.request())).isEqualTo("APPROVED");
    }

    @Test
    void onlyTheSameBranchManagerCanConfirmAnExternallyRefundedGatewayFailure() {
        Fixture failed = fixture("GATEWAY", "FAILED", 25_000);
        Fixture pending = fixture("GATEWAY", "REQUESTED", 25_000);
        assertThat(confirm(failed.refund(), staffToken, Map.of("note", "Đã hoàn")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirm(failed.refund(), token(staff("BRANCH_MANAGER", newBranch())), Map.of("note", "Đã hoàn"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map<String, Object>> missing = confirm(failed.refund(), managerToken, Map.of("note", " "));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertThat(confirm(pending.refund(), managerToken, Map.of("note", "Đã hoàn")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> result = data(confirm(failed.refund(), managerToken, Map.of("note", "Đã hoàn ngoài cổng")),
                HttpStatus.OK);
        assertThat(result).containsEntry("failureReason", "Từ chối từ cổng");
        assertSucceeded(failed, manager, null);
        assertThat(auditReason(failed.refund())).isEqualTo("Đã hoàn ngoài cổng");
        assertThat(refundStatus(pending.refund())).isEqualTo("REQUESTED");
    }

    @Test
    void rejectsAnInvalidLinkedReturnWithoutWritingTheRefundOrAudit() {
        Fixture fixture = fixture("CASH", "REQUESTED", 20_000);
        jdbc.update("UPDATE return_requests SET status = 'REFUNDED' WHERE id = ?", fixture.request());
        assertThat(confirm(fixture.refund(), staffToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refundStatus(fixture.refund())).isEqualTo("REQUESTED");
        assertThat(auditCount(fixture.refund())).isZero();
    }

    private Fixture fixture(String method, String status, long amount) {
        long orderId = pendingOrderSql(branch, customer, receptionist);
        long request = jdbc.queryForObject("""
                INSERT INTO return_requests (code, order_id, branch_id, status, reason_code, created_by,
                    decided_by, decided_at, restock, refund_amount)
                VALUES (?, ?, ?, 'APPROVED', 'DEFECTIVE', ?, ?, now(), false, ?) RETURNING id
                """, Long.class, code("TH"), orderId, branch, receptionist, manager, amount);
        String failure = status.equals("FAILED") ? "Từ chối từ cổng" : null;
        long refund = jdbc.queryForObject("""
                INSERT INTO refunds (code, return_request_id, branch_id, method, amount, status, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, code("HT"), request, branch, method, amount, status, failure);
        return new Fixture(request, refund);
    }

    private void assertSucceeded(Fixture fixture, long actor, Long shiftId) {
        assertThat(refundStatus(fixture.refund())).isEqualTo("SUCCEEDED");
        assertThat(returnStatus(fixture.request())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM refunds WHERE id = ?", Long.class, fixture.refund()))
                .isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT cashier_shift_id FROM refunds WHERE id = ?",
                Long.class, fixture.refund())).isEqualTo(shiftId);
        assertThat(auditCount(fixture.refund())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT actor_account_id FROM audit_logs
                WHERE action = 'REFUND_CONFIRMED' AND entity_type = 'refunds' AND entity_id = ?
                """, Long.class, fixture.refund())).isEqualTo(actor);
    }

    private long auditCount(long refund) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE action = 'REFUND_CONFIRMED' AND entity_type = 'refunds' AND entity_id = ?
                """, Long.class, refund);
    }

    private String auditReason(long refund) {
        return jdbc.queryForObject("""
                SELECT reason FROM audit_logs WHERE action = 'REFUND_CONFIRMED' AND entity_type = 'refunds' AND entity_id = ?
                """, String.class, refund);
    }

    private String refundStatus(long refund) {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE id = ?", String.class, refund);
    }

    private String returnStatus(long request) {
        return jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, request);
    }

    private ResponseEntity<Map<String, Object>> confirm(long refund, String bearer, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/refunds/" + refund + "/confirm", bearer, body);
    }

    private static String code(String prefix) {
        return prefix + "-IT-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private record Fixture(long request, long refund) {}
}
