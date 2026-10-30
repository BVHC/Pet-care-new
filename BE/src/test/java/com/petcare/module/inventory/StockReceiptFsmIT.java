package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/**
 * T45 — đủ 4 chuyển của bảng "9. Phiếu nhập kho" (docs/03-state-machines.md) qua endpoint thật, cùng mọi cặp sai có
 * đường HTTP (xác nhận / hủy / sửa từ trạng thái không cho phép), <b>không stand-in nào</b>. Trạng thái nguồn đi bằng
 * API (tạo → xác nhận → hủy), lô bị bán bớt đi bằng {@code POST /api/stock-adjustments}. Cặp sai → 409
 * {@code INVALID_STATE_TRANSITION} và không ghi gì: {@code xmin} của phiếu, số dòng, số lô, số movement đều không đổi.
 * Không job nào chạm phiếu nhập nên không cần era riêng; mỗi test có chi nhánh / sản phẩm / nhà cung cấp riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockReceiptFsmIT extends InventoryItSupport {

    private static final String PATH = "/api/stock-receipts";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private long branch;
    private long managerId;
    private String manager;
    private long supplier;
    private long goods;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        managerId = staff("BRANCH_MANAGER", branch);
        manager = token(managerId);
        supplier = newSupplier(true);
        goods = newProduct("GOODS", true);
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — tạo phiếu: {@code DRAFT}, chưa ảnh hưởng tồn. */
    @Test
    void createdReceiptIsDraftAndTouchesNoStock() {
        long id = draft();

        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(lotCount()).isZero();
        assertThat(movementsOfBranch()).isZero();
    }

    /** #2 — xác nhận: {@code CONFIRMED}, mỗi dòng thành một lô có đúng một movement RECEIPT. */
    @Test
    void confirmingAddsOneLotPerLineWithItsMovement() {
        long id = draft();

        data(confirm(id), HttpStatus.OK);

        assertThat(status(id)).isEqualTo("CONFIRMED");
        long lot = lotId("L-1");
        assertThat(lotQuantity(lot)).isEqualTo(10);
        assertThat(movementCount(lot)).isOne();
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM stock_receipts WHERE id = ?", Long.class, id))
                .isEqualTo(managerId);
    }

    /** #3 — hủy phiếu nháp: {@code CANCELLED}, không đổi tồn, giữ dòng phiếu (dãy số liên tục). */
    @Test
    void cancellingADraftChangesNoStock() {
        long id = draft();

        data(cancel(id), HttpStatus.OK);

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(lotCount()).isZero();
        assertThat(movementsOfBranch()).isZero();
        assertThat(count("SELECT count(*) FROM stock_receipts WHERE id = ?", id)).isOne();
    }

    /** #4 — hủy phiếu đã xác nhận: trừ lại đúng lô (thêm một movement), ghi một audit của phiếu. */
    @Test
    void cancellingAConfirmedReceiptReversesEachLot() {
        long id = confirmed();
        long lot = lotId("L-1");

        data(cancel(id), HttpStatus.OK);

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(lotQuantity(lot)).isZero();
        assertThat(movementCount(lot)).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM audit_logs WHERE action = 'STOCK_RECEIPT_CANCELLED'
                AND entity_type = 'stock_receipts' AND entity_id = ?""", id)).isOne();
    }

    /** #4, điều kiện — lô đã bán bớt thì không hủy được (BR-KO-04); phiếu, lô, movement không đổi. */
    @Test
    void cancellingAConfirmedReceiptAfterStockLeftIs400AndWritesNothing() {
        long id = confirmed();
        long lot = lotId("L-1");
        data(call(HttpMethod.POST, "/api/stock-adjustments", manager,
                Map.of("reason", "DAMAGED", "lines", List.of(Map.of("stockLotId", lot, "quantityDelta", -1)))),
                HttpStatus.CREATED);
        Map<String, Object> before = footprint(id);

        assertRule(cancel(id), "BR-KO-04");

        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(footprint(id)).isEqualTo(before);
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /** Mỗi nguồn: các thao tác không có cạnh tương ứng trong 03 (xác nhận / hủy / sửa) đều 409. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONFIRMED", "CANCELLED_DRAFT", "CANCELLED_CONFIRMED"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long id = reach(source);
        Map<String, Object> before = footprint(id);

        List<String> tried = switch (source) {
            case "CONFIRMED" -> List.of("confirm", "edit");
            default -> List.of("confirm", "cancel", "edit");
        };
        for (String operation : tried) {
            ResponseEntity<Map<String, Object>> response = attempt(operation, id);
            assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("statusCode", 409);
        }

        assertThat(footprint(id)).isEqualTo(before);
    }

    // ================================================================ helpers

    private long reach(String source) {
        return switch (source) {
            case "CONFIRMED" -> confirmed();
            case "CANCELLED_DRAFT" -> {
                long id = draft();
                data(cancel(id), HttpStatus.OK);
                yield id;
            }
            case "CANCELLED_CONFIRMED" -> {
                long id = confirmed();
                data(cancel(id), HttpStatus.OK);
                yield id;
            }
            default -> throw new IllegalArgumentException(source);
        };
    }

    private ResponseEntity<Map<String, Object>> attempt(String operation, long id) {
        return switch (operation) {
            case "confirm" -> confirm(id);
            case "cancel" -> cancel(id);
            case "edit" -> call(HttpMethod.PUT, PATH + "/" + id, manager, body());
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private Map<String, Object> body() {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", goods);
        line.put("quantity", 10);
        line.put("unitCost", 85000);
        line.put("lotNumber", "L-1");
        line.put("expiryDate", LocalDate.now(VN).plusDays(200).toString());
        Map<String, Object> body = new HashMap<>();
        body.put("supplierId", supplier);
        body.put("receiptDate", LocalDate.now(VN).toString());
        body.put("lines", List.of(line));
        return body;
    }

    private long draft() {
        return number(data(call(HttpMethod.POST, PATH, manager, body()), HttpStatus.CREATED).get("receiptId"));
    }

    private long confirmed() {
        long id = draft();
        data(confirm(id), HttpStatus.OK);
        return id;
    }

    private ResponseEntity<Map<String, Object>> confirm(long id) {
        return call(HttpMethod.POST, PATH + "/" + id + "/confirm", manager, null);
    }

    private ResponseEntity<Map<String, Object>> cancel(long id) {
        return call(HttpMethod.POST, PATH + "/" + id + "/cancel", manager, Map.of("reason", "Nhập nhầm"));
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, id);
    }

    private long lotId(String lotNumber) {
        return jdbc.queryForObject("""
                SELECT l.id FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND l.lot_number = ?""", Long.class, branch, lotNumber);
    }

    private long lotCount() {
        return count("""
                SELECT count(*) FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ?""", branch);
    }

    private long movementsOfBranch() {
        return count("""
                SELECT count(*) FROM stock_movements m JOIN stock_lots l ON l.id = m.stock_lot_id
                JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id = ?""", branch);
    }

    /** Dấu "không ghi gì": phiên bản dòng phiếu, số dòng phiếu, số lô, số movement, số audit hủy. */
    private Map<String, Object> footprint(long id) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("receipt", jdbc.queryForObject("SELECT xmin::text FROM stock_receipts WHERE id = ?", String.class, id));
        rows.put("lines", count("SELECT count(*) FROM stock_receipt_lines WHERE stock_receipt_id = ?", id));
        rows.put("lots", lotCount());
        rows.put("movements", movementsOfBranch());
        rows.put("lotRows", jdbc.queryForList("""
                SELECT l.id, l.quantity, l.xmin::text AS version FROM stock_lots l
                JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id = ? ORDER BY l.id""", branch));
        rows.put("audits", count("""
                SELECT count(*) FROM audit_logs WHERE action = 'STOCK_RECEIPT_CANCELLED'
                AND entity_type = 'stock_receipts' AND entity_id = ?""", id));
        return rows;
    }

    private long count(String sql, long arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }
}
