package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

/**
 * Điều chỉnh tồn — {@code /api/stock-adjustments} (UC76; inventory-v1 #15–17; BR-KO-01, 06; BR-QT-15; docs/adr/0042) qua
 * HTTP trên Postgres 17 thật: hợp đồng, quyền và phạm vi chi nhánh, movement {@code ADJUSTMENT}, audit, giảm quá tồn,
 * lô chi nhánh khác, tràn số, hai phiếu cùng giảm một lô, rollback khi audit lỗi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockAdjustmentIT extends InventoryItSupport {

    private static final String PATH = "/api/stock-adjustments";
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private long branch;
    private long managerId;
    private String manager;
    private long drug;
    private long goods;
    private long drugLot;
    private long goodsLot;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        managerId = staff("BRANCH_MANAGER", branch);
        manager = token(managerId);
        drug = newProduct("DRUG", true);
        goods = newProduct("GOODS", false);
        drugLot = lot(branch, drug, "L-1", LocalDate.now(ZONE).plusDays(60).toString(), 10, managerId);
        goodsLot = defaultLot(branch, goods, 20, managerId);
    }

    @AfterEach
    void dropFailingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_adjust_audit ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_adjust_audit()");
    }

    private static Map<String, Object> line(long lotId, int delta) {
        return Map.of("stockLotId", lotId, "quantityDelta", delta);
    }

    @SafeVarargs
    private ResponseEntity<Map<String, Object>> adjust(String reason, String note, Map<String, Object>... lines) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        if (note != null) {
            body.put("note", note);
        }
        body.put("lines", List.of(lines));
        return call(HttpMethod.POST, PATH, manager, body);
    }

    // ---------------------------------------------------------------- quyền, phạm vi

    @Test
    void onlyBranchManagersAdjustAndAnotherBranchCannotSee() {
        long id = number(data(adjust("DAMAGED", null, line(drugLot, -1)), HttpStatus.CREATED).get("adjustmentId"));
        for (String role : List.of("ADMIN", "SUPER_MANAGER", "RECEPTIONIST", "VET", "CARETAKER")) {
            String token = token(staff(role, branch));
            assertError(call(HttpMethod.POST, PATH, token, Map.of("reason", "DAMAGED", "lines",
                    List.of(line(drugLot, -1)))), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, PATH + "/" + id, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        String other = token(staff("BRANCH_MANAGER", newBranch()));
        assertError(call(HttpMethod.GET, PATH + "/" + id, other, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(data(call(HttpMethod.GET, PATH, other, null), HttpStatus.OK)).containsEntry("totalElements", 0);
    }

    // ---------------------------------------------------------------- lập phiếu

    @Test
    @SuppressWarnings("unchecked")
    void adjustmentTakesEffectAtOnceWithMovementsAndAudit() {
        ResponseEntity<Map<String, Object>> response = adjust("COUNT_MISMATCH", "  Kiểm đếm cuối tuần ",
                line(drugLot, -4), line(goodsLot, 5));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã lập phiếu điều chỉnh tồn");
        assertThat(data).containsOnlyKeys("adjustmentId", "code", "branchId", "reason", "note", "lines", "createdBy",
                        "createdAt")
                .containsEntry("reason", "COUNT_MISMATCH").containsEntry("note", "Kiểm đếm cuối tuần")
                .containsEntry("branchId", Math.toIntExact(branch)).containsEntry("createdBy", Math.toIntExact(managerId));
        assertThat((String) data.get("code")).matches("DC-\\d{4}-\\d{5}");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertThat(lines).hasSize(2);
        Map<Long, Map<String, Object>> byLot = new java.util.HashMap<>();
        lines.forEach(l -> byLot.put(number(l.get("stockLotId")), l));
        assertThat(byLot.get(drugLot)).containsOnlyKeys("stockLotId", "productId", "productName", "lotNumber",
                "quantityDelta", "balanceAfter").containsEntry("quantityDelta", -4).containsEntry("balanceAfter", 6)
                .containsEntry("lotNumber", "L-1");
        assertThat(byLot.get(goodsLot)).containsEntry("balanceAfter", 25).containsEntry("lotNumber", null);
        assertThat(lotQuantity(drugLot)).isEqualTo(6);
        assertThat(lotQuantity(goodsLot)).isEqualTo(25);
        long id = number(data.get("adjustmentId"));
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, quantity_delta, balance_after, source_type, source_id, created_by
                FROM stock_movements WHERE stock_lot_id = ? AND movement_type = 'ADJUSTMENT'
                """, drugLot)).containsEntry("quantity_delta", -4).containsEntry("balance_after", 6)
                .containsEntry("source_type", "stock_adjustments").containsEntry("source_id", id);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, after_data::text AS after_data FROM audit_logs
                WHERE action = 'STOCK_ADJUSTED' AND entity_id = ?
                """, id);
        assertThat(audit).containsEntry("actor_account_id", managerId).containsEntry("reason", "Kiểm đếm cuối tuần");
        assertThat((String) audit.get("after_data")).contains("COUNT_MISMATCH").contains("balanceAfter");
    }

    @Test
    void anExpiredLotCanBeWrittenOffAndDrainedToZero() {
        long expired = lot(branch, drug, "OLD", LocalDate.now(ZONE).minusDays(3).toString(), 4, managerId);

        data(adjust("EXPIRED", null, line(expired, -4)), HttpStatus.CREATED);

        assertThat(lotQuantity(expired)).isZero();
    }

    @Test
    void otherReasonNeedsANote() {
        assertRule(adjust("OTHER", null, line(drugLot, -1)), "BR-KO-06");
        assertRule(adjust("OTHER", "   ", line(drugLot, -1)), "BR-KO-06");
        data(adjust("OTHER", "Chuyển cho chi nhánh bạn", line(drugLot, -1)), HttpStatus.CREATED);
        assertThat(lotQuantity(drugLot)).isEqualTo(9);
    }

    @Test
    void reducingBelowTheLotBalanceNamesEveryLotAndChangesNothing() {
        long other = lot(branch, drug, "L-2", LocalDate.now(ZONE).plusDays(90).toString(), 3, managerId);

        ResponseEntity<Map<String, Object>> response = adjust("DAMAGED", null, line(drugLot, -11), line(other, -4),
                line(goodsLot, -1));

        assertRule(response, "BR-KO-06");
        assertThat((String) response.getBody().get("message")).contains("L-1").contains("L-2");
        assertThat(lotQuantity(drugLot)).isEqualTo(10);
        assertThat(lotQuantity(other)).isEqualTo(3);
        assertThat(lotQuantity(goodsLot)).as("không dòng nào được ghi").isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_adjustments WHERE branch_id = ?", Long.class,
                branch)).isZero();
    }

    @Test
    void addingBeyondTheIntRangeIsBrKo01() {
        jdbc.update("UPDATE stock_lots SET quantity = 2147000000 WHERE id = ?", goodsLot);

        assertRule(adjust("COUNT_MISMATCH", null, line(goodsLot, 1_000_000)), "BR-KO-01");
        assertThat(lotQuantity(goodsLot)).isEqualTo(2_147_000_000);
    }

    @Test
    void lotsOfAnotherBranchAndUnknownLotsAre404() {
        long otherBranch = newBranch();
        long foreignLot = defaultLot(otherBranch, newProduct("GOODS", false), 9, managerId);

        assertError(adjust("DAMAGED", null, line(foreignLot, -1)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(adjust("DAMAGED", null, line(999_999_999L, -1)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(lotQuantity(foreignLot)).isEqualTo(9);
    }

    @Test
    void invalidRequestsAreRejected() {
        Map<String, Object> noReason = new java.util.HashMap<>(Map.of("lines", List.of(line(drugLot, -1))));
        assertError(call(HttpMethod.POST, PATH, manager, noReason), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, PATH, manager, Map.of("reason", "DAMAGED", "lines", List.of())),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(adjust("DAMAGED", null, line(drugLot, 0)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(adjust("DAMAGED", null, line(drugLot, -1_000_001)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(adjust("DAMAGED", null, line(drugLot, -1), line(drugLot, -2)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(adjust("BROKEN", null, line(drugLot, -1)), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(lotQuantity(drugLot)).isEqualTo(10);
    }

    @Test
    void twoAdjustmentsReducingTheSameLotNeverMakeItNegative() {
        CompletableFuture<ResponseEntity<Map<String, Object>>> a = CompletableFuture
                .supplyAsync(() -> adjust("DAMAGED", null, line(drugLot, -6)));
        CompletableFuture<ResponseEntity<Map<String, Object>>> b = CompletableFuture
                .supplyAsync(() -> adjust("DAMAGED", null, line(drugLot, -6)));

        List<HttpStatus> statuses = List.of(HttpStatus.valueOf(a.join().getStatusCode().value()),
                HttpStatus.valueOf(b.join().getStatusCode().value()));

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        assertThat(lotQuantity(drugLot)).isEqualTo(4);
    }

    @Test
    void aFailingAuditInsertRollsBackTheAdjustment() {
        jdbc.execute("""
                CREATE FUNCTION it_fail_adjust_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'STOCK_ADJUSTED' THEN
                        RAISE EXCEPTION 'IT: audit insert fails';
                    END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER it_fail_adjust_audit BEFORE INSERT ON audit_logs FOR EACH ROW "
                + "EXECUTE FUNCTION it_fail_adjust_audit()");

        ResponseEntity<Map<String, Object>> response = adjust("DAMAGED", null, line(drugLot, -3));

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(lotQuantity(drugLot)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_adjustments WHERE branch_id = ?", Long.class,
                branch)).isZero();
        assertThat(movementCount(drugLot)).as("chỉ movement của dữ liệu mồi").isEqualTo(1L);
    }

    // ---------------------------------------------------------------- danh sách, chi tiết

    @Test
    @SuppressWarnings("unchecked")
    void listAndDetailShowLinesWithBalances() {
        long first = number(data(adjust("DAMAGED", null, line(drugLot, -1)), HttpStatus.CREATED).get("adjustmentId"));
        long second = number(data(adjust("DAMAGED", null, line(drugLot, -2), line(goodsLot, 3)), HttpStatus.CREATED)
                .get("adjustmentId"));
        String today = LocalDate.now(ZONE).toString();

        Map<String, Object> page = data(call(HttpMethod.GET, PATH + "?from=" + today + "&to=" + today, manager, null),
                HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");

        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("totalElements", 2);
        assertThat(content).extracting(c -> number(c.get("adjustmentId"))).containsExactly(second, first);
        List<Map<String, Object>> secondLines = (List<Map<String, Object>>) content.get(0).get("lines");
        assertThat(secondLines).extracting(l -> l.get("balanceAfter")).containsExactlyInAnyOrder(7, 23);
        Map<String, Object> detail = data(call(HttpMethod.GET, PATH + "/" + first, manager, null), HttpStatus.OK);
        assertThat(((List<Map<String, Object>>) detail.get("lines")).get(0)).containsEntry("balanceAfter", 9)
                .containsEntry("productName", jdbc.queryForObject("SELECT name FROM products WHERE id = ?",
                        String.class, drug));
        String past = LocalDate.now(ZONE).minusDays(30).toString();
        assertThat(data(call(HttpMethod.GET, PATH + "?to=" + past, manager, null), HttpStatus.OK))
                .containsEntry("totalElements", 0);
        assertError(call(HttpMethod.GET, PATH + "?from=2026-10-10&to=2026-10-01", manager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, PATH + "/999999999", manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertThat(data(call(HttpMethod.GET, PATH + "?size=1000&page=-1", manager, null), HttpStatus.OK))
                .containsEntry("size", 100).containsEntry("page", 0);
    }
}
