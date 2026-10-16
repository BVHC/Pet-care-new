package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Phiếu nhập kho — {@code /api/stock-receipts} (UC74; inventory-v1 #5–10; BR-KO-01…04, BR-SP-05; FSM 03 #9;
 * docs/adr/0042, 0043) qua HTTP trên Postgres 17 thật: hợp đồng, quyền và phạm vi chi nhánh, lỗi gom một message, cộng tồn
 * theo lô (gộp lô cũ, nhiều dòng cùng lô), movement, hủy trừ lại đúng lô, BR-KO-04, audit, rollback, đồng thời.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockReceiptIT extends InventoryItSupport {

    private static final String PATH = "/api/stock-receipts";
    private static final String TZ = "Asia/Ho_Chi_Minh";

    private long branch;
    private long managerId;
    private String manager;
    private long supplier;
    private long goods;
    private long drug;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        managerId = staff("BRANCH_MANAGER", branch);
        manager = token(managerId);
        supplier = newSupplier(true);
        goods = newProduct("GOODS", false);
        drug = newProduct("DRUG", true);
    }

    @AfterEach
    void dropFailingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_receipt_audit ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_receipt_audit()");
    }

    // ---------------------------------------------------------------- dữ liệu

    private static String today() {
        return LocalDate.now(ZoneId.of(TZ)).toString();
    }

    private static String inDays(int days) {
        return LocalDate.now(ZoneId.of(TZ)).plusDays(days).toString();
    }

    private static Map<String, Object> line(long productId, int quantity, long unitCost, String lot, String expiry) {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", productId);
        line.put("quantity", quantity);
        line.put("unitCost", unitCost);
        if (lot != null) {
            line.put("lotNumber", lot);
        }
        if (expiry != null) {
            line.put("expiryDate", expiry);
        }
        return line;
    }

    @SafeVarargs
    private Map<String, Object> receipt(String date, Map<String, Object>... lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplierId", supplier);
        body.put("receiptDate", date);
        body.put("lines", List.of(lines));
        return body;
    }

    @SafeVarargs
    private long draft(String date, Map<String, Object>... lines) {
        return number(data(call(HttpMethod.POST, PATH, manager, receipt(date, lines)), HttpStatus.CREATED)
                .get("receiptId"));
    }

    @SafeVarargs
    private long confirmed(String date, Map<String, Object>... lines) {
        long id = draft(date, lines);
        data(call(HttpMethod.POST, PATH + "/" + id + "/confirm", manager, null), HttpStatus.OK);
        return id;
    }

    private ResponseEntity<Map<String, Object>> confirm(long id) {
        return call(HttpMethod.POST, PATH + "/" + id + "/confirm", manager, null);
    }

    private ResponseEntity<Map<String, Object>> cancel(long id, Object body) {
        return call(HttpMethod.POST, PATH + "/" + id + "/cancel", manager, body);
    }

    private List<Map<String, Object>> lotsOf(long productId) {
        return jdbc.queryForList("""
                SELECT l.id, l.lot_number, l.expiry_date::text AS expiry_date, l.quantity, l.is_default
                FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND i.product_id = ? ORDER BY l.id
                """, branch, productId);
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void onlyBranchManagersUseReceipts() {
        long id = draft(today(), line(goods, 5, 1000, null, null));
        for (String role : List.of("ADMIN", "SUPER_MANAGER", "RECEPTIONIST", "VET", "CARETAKER")) {
            String token = token(staff(role, branch));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, PATH + "/" + id, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, PATH + "/" + id + "/confirm", token, null), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, PATH, token, receipt(today(), line(goods, 1, 1, null, null))),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void anotherBranchsManagerCannotTouchTheReceipt() {
        long id = draft(today(), line(goods, 5, 1000, null, null));
        String other = token(staff("BRANCH_MANAGER", newBranch()));

        assertError(call(HttpMethod.GET, PATH + "/" + id, other, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.POST, PATH + "/" + id + "/confirm", other, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.PUT, PATH + "/" + id, other, receipt(today(), line(goods, 1, 1, null, null))),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.POST, PATH + "/" + id + "/cancel", other, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(data(call(HttpMethod.GET, PATH, other, null), HttpStatus.OK)).containsEntry("totalElements", 0);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }

    // ---------------------------------------------------------------- tạo nháp

    @Test
    @SuppressWarnings("unchecked")
    void createDraftMatchesTheContractAndTouchesNoStock() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, manager,
                receipt(today(), line(goods, 10, 85000, null, null), line(drug, 5, 120000, "  L-1 ", inDays(200))));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã tạo phiếu nhập nháp");
        assertThat(data).containsOnlyKeys("receiptId", "code", "branchId", "supplierId", "supplierName", "receiptDate",
                        "status", "note", "lines", "createdBy", "confirmedBy", "confirmedAt", "cancelledBy",
                        "cancelledAt", "cancelReason")
                .containsEntry("status", "DRAFT").containsEntry("branchId", Math.toIntExact(branch))
                .containsEntry("createdBy", Math.toIntExact(managerId)).containsEntry("confirmedBy", null);
        assertThat((String) data.get("code")).matches("PN-\\d{4}-\\d{5}");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsOnlyKeys("lineId", "productId", "productName", "quantity", "unitCost",
                "lotNumber", "expiryDate", "stockLotId").containsEntry("stockLotId", null);
        assertThat(lines.get(1)).containsEntry("lotNumber", "L-1").containsEntry("expiryDate", inDays(200));
        assertThat(lotsOf(goods)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items WHERE branch_id = ?", Long.class,
                branch)).isZero();
    }

    @Test
    void codesAreConsecutive() {
        String first = (String) data(call(HttpMethod.POST, PATH, manager, receipt(today(),
                line(goods, 1, 1, null, null))), HttpStatus.CREATED).get("code");
        String second = (String) data(call(HttpMethod.POST, PATH, manager, receipt(today(),
                line(goods, 1, 1, null, null))), HttpStatus.CREATED).get("code");

        assertThat(Integer.parseInt(second.substring(8))).isEqualTo(Integer.parseInt(first.substring(8)) + 1);
        assertThat(second.substring(0, 8)).isEqualTo(first.substring(0, 8));
    }

    @Test
    void createRejectsInvalidInputAndWritesNothing() {
        long before = jdbc.queryForObject("SELECT count(*) FROM stock_receipts", Long.class);
        List<Map<String, Object>> bodies = new ArrayList<>();
        Map<String, Object> noSupplier = receipt(today(), line(goods, 1, 1, null, null));
        noSupplier.remove("supplierId");
        bodies.add(noSupplier);
        Map<String, Object> noLines = receipt(today());
        bodies.add(noLines);
        bodies.add(receipt(today(), line(goods, 0, 1, null, null)));
        bodies.add(receipt(today(), line(goods, 1_000_001, 1, null, null)));
        bodies.add(receipt(today(), line(goods, 1, -1, null, null)));
        bodies.add(receipt(today(), line(goods, 1, 1, "x".repeat(51), null)));
        Map<String, Object> noDate = receipt(today(), line(goods, 1, 1, null, null));
        noDate.remove("receiptDate");
        bodies.add(noDate);

        for (Map<String, Object> body : bodies) {
            assertError(call(HttpMethod.POST, PATH, manager, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertError(callRaw(HttpMethod.POST, PATH, manager, "{bad"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(callRaw(HttpMethod.POST, PATH, manager, "{\"supplierId\":1,\"receiptDate\":\"16/10/2026\","
                + "\"lines\":[]}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_receipts", Long.class)).isEqualTo(before);
    }

    @Test
    void createNeedsAnExistingActiveSupplierAndExistingProducts() {
        Map<String, Object> body = receipt(today(), line(goods, 1, 1, null, null));
        body.put("supplierId", 999_999_999L);
        assertError(call(HttpMethod.POST, PATH, manager, body), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        body.put("supplierId", newSupplier(false));
        assertRule(call(HttpMethod.POST, PATH, manager, body), "BR-KO-02");

        assertError(call(HttpMethod.POST, PATH, manager, receipt(today(), line(999_999_999L, 1, 1, null, null))),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- sửa nháp

    @Test
    @SuppressWarnings("unchecked")
    void updateReplacesTheWholeDraftAndKeepsTheCode() {
        long id = draft(today(), line(goods, 10, 1000, null, null));
        String code = (String) data(call(HttpMethod.GET, PATH + "/" + id, manager, null), HttpStatus.OK).get("code");
        long other = newSupplier(true);
        Map<String, Object> body = receipt(inDays(-1), line(drug, 3, 5000, "L-9", inDays(90)));
        body.put("supplierId", other);
        body.put("note", "  Ghi chú mới ");

        Map<String, Object> data = data(call(HttpMethod.PUT, PATH + "/" + id, manager, body), HttpStatus.OK);

        assertThat(data).containsEntry("code", code).containsEntry("supplierId", Math.toIntExact(other))
                .containsEntry("note", "Ghi chú mới").containsEntry("receiptDate", inDays(-1));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("productId", Math.toIntExact(drug));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_receipt_lines WHERE stock_receipt_id = ?",
                Long.class, id)).isOne();
    }

    @Test
    void onlyDraftsCanBeEdited() {
        long confirmed = confirmed(today(), line(goods, 5, 1000, null, null));
        long cancelled = draft(today(), line(goods, 5, 1000, null, null));
        data(cancel(cancelled, null), HttpStatus.OK);

        for (long id : new long[] {confirmed, cancelled}) {
            assertError(call(HttpMethod.PUT, PATH + "/" + id, manager, receipt(today(), line(goods, 1, 1, null, null))),
                    HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        }
        assertError(call(HttpMethod.PUT, PATH + "/999999999", manager, receipt(today(), line(goods, 1, 1, null, null))),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_receipt_lines WHERE stock_receipt_id = ?",
                Integer.class, confirmed)).isEqualTo(5);
    }

    // ---------------------------------------------------------------- xác nhận

    @Test
    @SuppressWarnings("unchecked")
    void confirmAddsStockPerLotWritesMovementsAndLinksTheLines() {
        long id = draft(today(), line(goods, 10, 85000, null, null), line(drug, 5, 120000, "L-1", inDays(200)));

        Map<String, Object> data = data(confirm(id), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CONFIRMED").containsEntry("confirmedBy", Math.toIntExact(managerId));
        assertThat(data.get("confirmedAt")).isNotNull();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        assertThat(lines).allSatisfy(l -> assertThat(l.get("stockLotId")).isNotNull());
        List<Map<String, Object>> goodsLots = lotsOf(goods);
        assertThat(goodsLots).singleElement().satisfies(l -> {
            assertThat(l).containsEntry("is_default", true).containsEntry("quantity", 10);
            assertThat(l.get("lot_number")).isNull();
        });
        assertThat(lotsOf(drug)).singleElement().satisfies(l -> assertThat(l).containsEntry("lot_number", "L-1")
                .containsEntry("expiry_date", inDays(200)).containsEntry("quantity", 5).containsEntry("is_default", false));
        long goodsLot = number(goodsLots.get(0).get("id"));
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, quantity_delta, balance_after, source_type, source_id, created_by
                FROM stock_movements WHERE stock_lot_id = ?
                """, goodsLot)).containsEntry("movement_type", "RECEIPT").containsEntry("quantity_delta", 10)
                .containsEntry("balance_after", 10).containsEntry("source_type", "stock_receipts")
                .containsEntry("source_id", id).containsEntry("created_by", managerId);
    }

    @Test
    void sameLotAndExpiryMergesIntoTheExistingLotAcrossAndWithinReceipts() {
        confirmed(today(), line(drug, 5, 1000, "L-1", inDays(100)));

        long second = confirmed(today(), line(drug, 7, 1000, "L-1", inDays(100)), line(drug, 3, 1000, "L-1", inDays(100)),
                line(drug, 4, 1000, "L-2", inDays(100)));

        List<Map<String, Object>> lots = lotsOf(drug);
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0)).containsEntry("lot_number", "L-1").containsEntry("quantity", 15);
        assertThat(lots.get(1)).containsEntry("lot_number", "L-2").containsEntry("quantity", 4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements WHERE source_type = 'stock_receipts' AND source_id = ?
                """, Long.class, second)).as("một movement mỗi lô").isEqualTo(2L);
    }

    @Test
    void confirmCollectsEveryLineProblemIntoOneBrKo03Message() {
        long id = draft(today(), line(drug, 5, 1000, null, null), line(drug, 5, 1000, "L-1", today()),
                line(goods, 5, 1000, "L-2", inDays(30)));

        ResponseEntity<Map<String, Object>> response = confirm(id);

        assertRule(response, "BR-KO-03");
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("dòng 1").contains("thiếu số lô").contains("thiếu hạn dùng").contains("dòng 2")
                .contains("không sau ngày nhập").contains("dòng 3").contains("không quản lý hạn dùng");
        assertThat(lotsOf(drug)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }

    @Test
    void futureReceiptDateCannotBeConfirmed() {
        long id = draft(inDays(2), line(goods, 5, 1000, null, null));

        assertRule(confirm(id), "BR-KO-03");
        assertThat(lotsOf(goods)).isEmpty();
    }

    @Test
    void aSupplierWhoStoppedCooperatingBlocksTheConfirmation() {
        long id = draft(today(), line(goods, 5, 1000, null, null));
        jdbc.update("UPDATE suppliers SET is_active = false WHERE id = ?", supplier);

        assertRule(confirm(id), "BR-KO-02");
        assertThat(lotsOf(goods)).isEmpty();
    }

    @Test
    void confirmingTwiceOrACancelledReceiptIs409() {
        long id = confirmed(today(), line(goods, 5, 1000, null, null));
        long cancelled = draft(today(), line(goods, 5, 1000, null, null));
        data(cancel(cancelled, null), HttpStatus.OK);

        assertError(confirm(id), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(confirm(cancelled), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(confirm(999_999_999L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(lotsOf(goods)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 5));
    }

    @Test
    void aFlagTurnedOffAfterTheDraftCannotKeepALotNumber() {
        long id = draft(today(), line(drug, 5, 1000, "L-1", inDays(100)));
        jdbc.update("UPDATE products SET tracks_expiry = false, is_prescription = false WHERE id = ?", drug);

        assertRule(confirm(id), "BR-KO-03");
    }

    @Test
    void twoReceiptsCreatingTheSameNewLotAtOnceBothSucceed() {
        long first = draft(today(), line(drug, 5, 1000, "L-RACE", inDays(100)));
        long second = draft(today(), line(drug, 7, 1000, "L-RACE", inDays(100)));

        CompletableFuture<ResponseEntity<Map<String, Object>>> a = CompletableFuture.supplyAsync(() -> confirm(first));
        CompletableFuture<ResponseEntity<Map<String, Object>>> b = CompletableFuture.supplyAsync(() -> confirm(second));

        assertThat(a.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lotsOf(drug)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 12));
        assertThat(movementCount(number(lotsOf(drug).get(0).get("id")))).isEqualTo(2L);
    }

    // ---------------------------------------------------------------- hủy

    @Test
    void cancellingADraftChangesNoStockAndKeepsTheRow() {
        long id = draft(today(), line(goods, 5, 1000, null, null));

        Map<String, Object> data = data(cancel(id, Map.of("reason", "  Nhập nhầm ")), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CANCELLED").containsEntry("cancelReason", "Nhập nhầm")
                .containsEntry("cancelledBy", Math.toIntExact(managerId));
        assertThat(lotsOf(goods)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_receipts WHERE id = ?", Long.class, id)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'STOCK_RECEIPT_CANCELLED' "
                + "AND entity_id = ?", Long.class, id)).as("hủy nháp không audit").isZero();
    }

    @Test
    void cancellingAConfirmedReceiptTakesTheStockBackFromTheSameLotsAndAudits() {
        long id = confirmed(today(), line(goods, 10, 1000, null, null), line(drug, 5, 1000, "L-1", inDays(100)),
                line(drug, 2, 1000, "L-1", inDays(100)));
        long drugLot = number(lotsOf(drug).get(0).get("id"));

        Map<String, Object> data = data(cancel(id, null), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CANCELLED").containsEntry("cancelReason", null);
        assertThat(lotsOf(goods)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 0));
        assertThat(lotsOf(drug)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 0));
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, quantity_delta, balance_after FROM stock_movements
                WHERE stock_lot_id = ? AND movement_type = 'RECEIPT_CANCEL'
                """, drugLot)).containsEntry("quantity_delta", -7).containsEntry("balance_after", 0);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE action = 'STOCK_RECEIPT_CANCELLED' AND entity_id = ?
                """, id);
        assertThat(audit).containsEntry("actor_account_id", managerId);
        assertThat((String) audit.get("before_data")).contains("CONFIRMED");
        assertThat((String) audit.get("after_data")).contains("CANCELLED");
    }

    @Test
    void cancelIsRefusedWhenALotWasPartlySoldAndNothingChanges() {
        long id = confirmed(today(), line(goods, 10, 1000, null, null), line(drug, 5, 1000, "L-1", inDays(100)));
        jdbc.update("UPDATE stock_lots SET quantity = 3 WHERE id = ?", number(lotsOf(drug).get(0).get("id")));

        ResponseEntity<Map<String, Object>> response = cancel(id, null);

        assertRule(response, "BR-KO-04");
        assertThat((String) response.getBody().get("message")).contains("còn 3").contains("cần trừ 5")
                .contains("BR-KO-06");
        assertThat(lotsOf(goods)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 10));
        assertThat(jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, id))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE movement_type = 'RECEIPT_CANCEL' "
                + "AND source_id = ?", Long.class, id)).isZero();
    }

    @Test
    void cancellingTwiceIs409() {
        long id = confirmed(today(), line(goods, 5, 1000, null, null));
        data(cancel(id, null), HttpStatus.OK);

        assertError(cancel(id, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(cancel(999_999_999L, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(cancel(id, Map.of("reason", "x".repeat(301))), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void anExpiredLotCanStillBeTakenBackByCancelling() {
        long id = confirmed(today(), line(drug, 5, 1000, "L-1", inDays(100)));
        jdbc.update("UPDATE stock_lots SET expiry_date = current_date - 5 WHERE id = ?",
                number(lotsOf(drug).get(0).get("id")));

        data(cancel(id, null), HttpStatus.OK);

        assertThat(lotsOf(drug)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 0));
    }

    @Test
    void aFailingAuditInsertRollsBackTheCancellation() {
        long id = confirmed(today(), line(goods, 10, 1000, null, null));
        jdbc.execute("""
                CREATE FUNCTION it_fail_receipt_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'STOCK_RECEIPT_CANCELLED' THEN
                        RAISE EXCEPTION 'IT: audit insert fails';
                    END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER it_fail_receipt_audit BEFORE INSERT ON audit_logs FOR EACH ROW "
                + "EXECUTE FUNCTION it_fail_receipt_audit()");

        ResponseEntity<Map<String, Object>> response = cancel(id, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(lotsOf(goods)).singleElement().satisfies(l -> assertThat(l).containsEntry("quantity", 10));
        assertThat(jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, id))
                .isEqualTo("CONFIRMED");
    }

    // ---------------------------------------------------------------- danh sách, chi tiết

    @Test
    @SuppressWarnings("unchecked")
    void listFiltersAndTotalsAndIsScopedToTheBranch() {
        long other = newSupplier(true);
        long a = draft("2026-09-10", line(goods, 2, 1500, null, null), line(goods, 3, 1000, null, null));
        long b = confirmed("2026-09-20", line(goods, 1, 7000, null, null));
        Map<String, Object> body = receipt("2026-09-30", line(goods, 1, 1, null, null));
        body.put("supplierId", other);
        long c = number(data(call(HttpMethod.POST, PATH, manager, body), HttpStatus.CREATED).get("receiptId"));
        String foreign = token(staff("BRANCH_MANAGER", newBranch()));
        call(HttpMethod.POST, PATH, foreign, receipt("2026-09-15", line(goods, 1, 1, null, null)));

        Map<String, Object> page = data(call(HttpMethod.GET, PATH + "?size=50", manager, null), HttpStatus.OK);

        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("totalElements", 3);
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).extracting(r -> number(r.get("receiptId"))).containsExactly(c, b, a);
        assertThat(content.get(2)).containsOnlyKeys("receiptId", "code", "supplierName", "receiptDate", "status",
                "lineCount", "totalCost").containsEntry("lineCount", 2).containsEntry("totalCost", 6000);
        assertThat(ids(PATH + "?status=DRAFT")).containsExactlyInAnyOrder(a, c);
        assertThat(ids(PATH + "?status=CONFIRMED")).containsExactly(b);
        assertThat(ids(PATH + "?supplierId=" + other)).containsExactly(c);
        assertThat(ids(PATH + "?from=2026-09-15&to=2026-09-25")).containsExactly(b);
        assertThat(ids(PATH + "?from=2026-09-20&to=2026-09-20")).as("khoảng đóng").containsExactly(b);
        assertError(call(HttpMethod.GET, PATH + "?from=2026-10-01&to=2026-09-01", manager, null), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, PATH + "?status=WHATEVER", manager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        Map<String, Object> clamped = data(call(HttpMethod.GET, PATH + "?size=1000&page=-3", manager, null),
                HttpStatus.OK);
        assertThat(clamped).containsEntry("size", 100).containsEntry("page", 0);
    }

    @SuppressWarnings("unchecked")
    private List<Long> ids(String url) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) data(call(HttpMethod.GET, url, manager, null),
                HttpStatus.OK).get("content");
        return content.stream().map(r -> number(r.get("receiptId"))).toList();
    }

    @Test
    void detailOfAMissingReceiptIs404() {
        assertError(call(HttpMethod.GET, PATH + "/999999999", manager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }
}
