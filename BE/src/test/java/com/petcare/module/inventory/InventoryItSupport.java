package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.identity.service.SessionService;

/**
 * Dữ liệu và HTTP dùng chung cho IT của module kho (docs/adr/0042). Mỗi lớp IT tạo chi nhánh riêng, nhân viên, sản phẩm,
 * nhà cung cấp riêng (đánh dấu bằng UUID) rồi {@link #cleanInventory()} xóa mọi dòng kho gắn với các chi nhánh / sản phẩm /
 * nhà cung cấp đó, nên không đụng dữ liệu của IT khác. Token mở bằng {@link SessionService#open}.
 */
public abstract class InventoryItSupport {

    protected static final AtomicInteger SEQ = new AtomicInteger();
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected SessionService sessions;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    private final List<Long> branches = new ArrayList<>();
    private final List<Long> products = new ArrayList<>();
    private final List<Long> suppliers = new ArrayList<>();
    private Long categoryId;

    @AfterEach
    void cleanInventory() {
        if (!branches.isEmpty()) {
            String b = ids(branches);
            jdbc.update("DELETE FROM stock_movements WHERE stock_lot_id IN (SELECT l.id FROM stock_lots l "
                    + "JOIN inventory_items i ON i.id = l.inventory_item_id WHERE i.branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM stock_adjustment_lines WHERE stock_adjustment_id IN "
                    + "(SELECT id FROM stock_adjustments WHERE branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM stock_adjustments WHERE branch_id IN (" + b + ")");
            jdbc.update("DELETE FROM stock_receipt_lines WHERE stock_receipt_id IN "
                    + "(SELECT id FROM stock_receipts WHERE branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM stock_receipts WHERE branch_id IN (" + b + ")");
            jdbc.update("DELETE FROM stock_lots WHERE inventory_item_id IN "
                    + "(SELECT id FROM inventory_items WHERE branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM inventory_items WHERE branch_id IN (" + b + ")");
        }
        if (!suppliers.isEmpty()) {
            jdbc.update("DELETE FROM stock_receipt_lines WHERE stock_receipt_id IN "
                    + "(SELECT id FROM stock_receipts WHERE supplier_id IN (" + ids(suppliers) + "))");
            jdbc.update("DELETE FROM stock_receipts WHERE supplier_id IN (" + ids(suppliers) + ")");
            jdbc.update("DELETE FROM suppliers WHERE id IN (" + ids(suppliers) + ")");
        }
        if (!products.isEmpty()) {
            jdbc.update("DELETE FROM inventory_items WHERE product_id IN (" + ids(products) + ")");
            jdbc.update("DELETE FROM products WHERE id IN (" + ids(products) + ")");
        }
        branches.clear();
        suppliers.clear();
        products.clear();
    }

    private static String ids(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    // ---------------------------------------------------------------- dữ liệu

    protected long newBranch() {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'ACTIVE') RETURNING id
                """, Long.class, "KhoIT chi nhánh " + UUID.randomUUID());
        branches.add(id);
        return id;
    }

    /** Nhân viên {@code ACTIVE}; A05–A08 thuộc {@code branchId}, ADMIN / SUPER_MANAGER không có chi nhánh. */
    protected long staff(String role, Long branchId) {
        boolean scoped = role.equals("BRANCH_MANAGER") || role.equals("RECEPTIONIST") || role.equals("VET")
                || role.equals("CARETAKER");
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "kho-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                "Kho IT " + role, scoped ? branchId : null);
        return id;
    }

    protected String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "InventoryIT")).accessToken();
    }

    /** Sản phẩm riêng. {@code type}: GOODS, DRUG, VACCINE (VACCINE kéo theo loại vaccine riêng và {@code tracksExpiry}). */
    protected long newProduct(String type, boolean tracksExpiry) {
        if (categoryId == null) {
            categoryId = jdbc.queryForObject("""
                    INSERT INTO product_categories (name) VALUES (?) RETURNING id
                    """, Long.class, "KhoIT danh mục " + UUID.randomUUID());
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long vaccineTypeId = null;
        if (type.equals("VACCINE")) {
            vaccineTypeId = jdbc.queryForObject("""
                    INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id
                    """, Long.class, "KhoIT vaccine " + suffix);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, vaccine_type_id, unit, price)
                VALUES (?, ?, ?, ?, ?, ?, 'hộp', 100000) RETURNING id
                """, Long.class, categoryId, "KHO-" + suffix, "Sản phẩm kho " + suffix, type,
                tracksExpiry || type.equals("VACCINE"), vaccineTypeId);
        products.add(id);
        return id;
    }

    protected long newSupplier(boolean active) {
        long id = jdbc.queryForObject("""
                INSERT INTO suppliers (name, phone, is_active) VALUES (?, '0281234567', ?) RETURNING id
                """, Long.class, "NCC kho " + UUID.randomUUID().toString().substring(0, 8), active);
        suppliers.add(id);
        return id;
    }

    /** Ghi nhận nhà cung cấp tạo qua API để {@link #cleanInventory()} dọn. */
    protected long trackSupplier(long id) {
        suppliers.add(id);
        return id;
    }

    protected long inventoryItem(long branchId, long productId) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?)
                ON CONFLICT (branch_id, product_id) DO UPDATE SET min_quantity = inventory_items.min_quantity
                RETURNING id
                """, Long.class, branchId, productId);
    }

    /** Lô có hạn dùng với số dư {@code quantity}; kèm một movement RECEIPT cho đúng nguyên tắc 5 (04). */
    protected long lot(long branchId, long productId, String lotNumber, String expiryDate, int quantity, long actor) {
        long item = inventoryItem(branchId, productId);
        long lot = jdbc.queryForObject("""
                INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
                VALUES (?, ?, ?::date, ?, false) RETURNING id
                """, Long.class, item, lotNumber, expiryDate, quantity);
        recordSeedMovement(lot, quantity, actor);
        return lot;
    }

    protected long defaultLot(long branchId, long productId, int quantity, long actor) {
        long item = inventoryItem(branchId, productId);
        long lot = jdbc.queryForObject("""
                INSERT INTO stock_lots (inventory_item_id, quantity, is_default) VALUES (?, ?, true) RETURNING id
                """, Long.class, item, quantity);
        recordSeedMovement(lot, quantity, actor);
        return lot;
    }

    private void recordSeedMovement(long lot, int quantity, long actor) {
        if (quantity > 0) {
            jdbc.update("""
                    INSERT INTO stock_movements (stock_lot_id, movement_type, quantity_delta, balance_after, source_type,
                                                 source_id, created_by)
                    VALUES (?, 'RECEIPT', ?, ?, 'stock_receipts', 0, ?)
                    """, lot, quantity, quantity, actor);
        }
    }

    protected int lotQuantity(long lotId) {
        return jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Integer.class, lotId);
    }

    protected long movementCount(long lotId) {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE stock_lot_id = ?", Long.class, lotId);
    }

    // ---------------------------------------------------------------- HTTP

    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected ResponseEntity<Map<String, Object>> callRaw(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return map(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> list(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    protected static long number(Object value) {
        return ((Number) value).longValue();
    }

    protected static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    protected static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }
}
