package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Danh sách, chi tiết và đối soát ca thu ngân (sales-v1 #19–#21; UC72; Ca thu ngân#6; BR-TG-04, 05, BR-QT-15;
 * docs/adr/0064) trên Postgres 17 thật qua HTTP: lọc / sắp xếp / phạm vi chi nhánh, quyền xem chi tiết theo vai trò,
 * chênh lệch, ghi đè số thực đếm, ca tự chốt, audit và rollback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class CashierShiftReadReconcileIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    private static final Instant OPENED = Instant.parse("2005-06-15T01:00:00Z");

    @Autowired
    private MutableClock clock;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private String tReceptionist;
    private String tManager;
    private String tVet;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        tReceptionist = token(receptionist);
        tManager = token(manager);
        tVet = token(staff("VET", branch));
    }

    private ResponseEntity<Map<String, Object>> get(String token, String path) {
        return call(HttpMethod.GET, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) data(response, HttpStatus.OK).get("content");
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, HttpStatus status, String rule) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat((String) response.getBody().get("message")).endsWith("(" + rule + ")");
    }

    private long closedShiftSql(long branchId, long cashierId, LocalDate day, Long counted, boolean auto) {
        long id = openShiftSql(branchId, cashierId, false, day, OPENED);
        jdbc.update("""
                UPDATE cashier_shifts SET status = 'CLOSED', closed_at = now(), auto_closed = ?, expected_cash = 500000,
                       expected_transfer = 0, counted_cash = ? WHERE id = ?""", auto, counted, id);
        return id;
    }

    // ---------------------------------------------------------------- #19 danh sách

    @Test
    void listFiltersSortsByBusinessDateDescAndCountsPayments() {
        long cashier2 = receptionist(branch);
        long customer = newCustomer();
        long older = closedShiftSql(branch, receptionist, LocalDate.parse("2005-06-14"), 500_000L, false);
        long newer = closedShiftSql(branch, cashier2, DAY, 500_000L, false);
        paymentSql(branch, customer, newer, "CASH", 1000, cashier2);
        paymentSql(branch, customer, newer, "TRANSFER", 2000, cashier2);
        long open = openShiftSql(branch, receptionist(branch), false, DAY, OPENED);

        List<Map<String, Object>> all = content(get(tManager, "/api/cashier-shifts"));

        assertThat(all).extracting(s -> ((Number) s.get("shiftId")).longValue())
                .containsExactlyInAnyOrder(older, newer, open);
        assertThat(((Number) all.get(all.size() - 1).get("shiftId")).longValue()).isEqualTo(older);
        assertThat(all).filteredOn(s -> ((Number) s.get("shiftId")).longValue() == newer)
                .extracting(s -> s.get("paymentCount")).containsExactly(2);
        assertThat(content(get(tManager, "/api/cashier-shifts?status=OPEN"))).extracting(s -> s.get("shiftId"))
                .containsExactly((int) open);
        assertThat(content(get(tManager, "/api/cashier-shifts?businessDate=2005-06-14")))
                .extracting(s -> s.get("shiftId")).containsExactly((int) older);
        assertThat(content(get(tManager, "/api/cashier-shifts?cashierId=" + cashier2)))
                .extracting(s -> s.get("shiftId")).containsExactly((int) newer);
    }

    @Test
    void listShowsOnlyTheManagersBranchAndRefusesOtherRoles() {
        long foreign = openShiftSql(otherBranch, receptionist(otherBranch), false, DAY, OPENED);

        assertThat(content(get(tManager, "/api/cashier-shifts"))).extracting(s -> s.get("shiftId"))
                .doesNotContain((int) foreign);
        assertThat(get(tReceptionist, "/api/cashier-shifts").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(tVet, "/api/cashier-shifts").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(tManager, "/api/cashier-shifts?status=BOGUS").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- #20 chi tiết

    @Test
    void detailRulesByRole() {
        long mine = openShiftSql(branch, receptionist, false, DAY, OPENED);
        long others = closedShiftSql(branch, receptionist(branch), DAY, 1L, false);
        long foreign = openShiftSql(otherBranch, receptionist(otherBranch), false, DAY, OPENED);

        assertThat(data(get(tReceptionist, "/api/cashier-shifts/" + mine), HttpStatus.OK))
                .containsEntry("shiftId", (int) mine);
        assertThat(get(tReceptionist, "/api/cashier-shifts/" + others).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(tManager, "/api/cashier-shifts/" + others).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(tManager, "/api/cashier-shifts/" + foreign).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(tManager, "/api/cashier-shifts/" + Long.MAX_VALUE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(tVet, "/api/cashier-shifts/" + mine).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- #21 đối soát

    private ResponseEntity<Map<String, Object>> reconcile(String token, long shiftId, Object countedCash,
            String note) {
        Map<String, Object> body = new HashMap<>();
        if (countedCash != null) {
            body.put("countedCash", countedCash);
        }
        if (note != null) {
            body.put("note", note);
        }
        return call(HttpMethod.POST, "/api/cashier-shifts/" + shiftId + "/reconcile", token, body);
    }

    private long auditCount(long shiftId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'CASHIER_SHIFT_RECONCILED' AND entity_id = ?""",
                Long.class, shiftId);
    }

    @Test
    void reconcileKeepsTheCashierCountAndWritesDifferenceAndAudit() {
        long shift = closedShiftSql(branch, receptionist, DAY, 480_000L, false);

        Map<String, Object> reconciled = data(reconcile(tManager, shift, null, "  Thiếu 20k  "), HttpStatus.OK);

        assertThat(reconciled).containsEntry("status", "RECONCILED").containsEntry("difference", -20_000)
                .containsEntry("countedCash", 480_000).containsEntry("reconcileNote", "Thiếu 20k")
                .containsEntry("reconciledBy", (int) manager);
        assertThat(reconciled.get("reconciledAt")).isNotNull();
        assertThat(shiftRow(shift)).containsEntry("status", "RECONCILED").containsEntry("difference", -20_000L);
        assertThat(auditCount(shift)).isEqualTo(1L);
    }

    @Test
    void reconcileCanOverrideTheCountAndAuditKeepsTheOldValue() {
        long shift = closedShiftSql(branch, receptionist, DAY, 480_000L, false);

        Map<String, Object> reconciled = data(reconcile(tManager, shift, 500_000, "Đếm lại"), HttpStatus.OK);

        assertThat(reconciled).containsEntry("countedCash", 500_000).containsEntry("difference", 0);
        assertThat(jdbc.queryForObject("""
                SELECT before_data->>'countedCash' FROM audit_logs
                WHERE action = 'CASHIER_SHIFT_RECONCILED' AND entity_id = ?""", String.class, shift))
                .isEqualTo("480000");
    }

    @Test
    void autoClosedShiftNeedsACountToReconcile() {
        long shift = closedShiftSql(branch, receptionist, DAY, null, true);

        assertRule(reconcile(tManager, shift, null, "ghi chú"), HttpStatus.BAD_REQUEST, "BR-TG-05");
        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED");
        assertThat(auditCount(shift)).isZero();

        Map<String, Object> reconciled = data(reconcile(tManager, shift, 450_000, "Quản lý đếm hộ"), HttpStatus.OK);
        assertThat(reconciled).containsEntry("difference", -50_000).containsEntry("autoClosed", true);
    }

    @Test
    void reconcileRefusesAnOpenOrAlreadyReconciledShift() {
        long open = openShiftSql(branch, receptionist(branch), false, DAY, OPENED);
        long shift = closedShiftSql(branch, receptionist(branch), DAY, 1L, false);
        data(reconcile(tManager, shift, null, "lần một"), HttpStatus.OK);

        assertThat(reconcile(tManager, open, 1, "x").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reconcile(tManager, shift, 1, "lần hai").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditCount(shift)).as("đúng một dòng audit").isEqualTo(1L);
    }

    @Test
    void reconcileValidationAndRoles() {
        long shift = closedShiftSql(branch, receptionist, DAY, 1L, false);
        long foreign = closedShiftSql(otherBranch, receptionist(otherBranch), DAY, 1L, false);

        assertThat(reconcile(tManager, shift, null, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reconcile(tManager, shift, null, "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reconcile(tManager, shift, -1, "x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reconcile(tReceptionist, shift, 1, "x").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reconcile(tManager, foreign, 1, "x").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reconcile(tManager, Long.MAX_VALUE, 1, "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED");
    }

    @Test
    void reconcileRollsBackWhenTheAuditInsertFails() {
        long shift = closedShiftSql(branch, receptionist, DAY, 1L, false);

        whileInsertsFail("audit_logs", "NEW.action = 'CASHIER_SHIFT_RECONCILED'", () -> assertThat(
                reconcile(tManager, shift, null, "x").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED").containsEntry("difference", null);
    }
}
