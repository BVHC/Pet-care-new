package com.petcare.module.visit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dữ liệu mũi tiêm cho IT (T28 phần 0, docs/adr/0072): loại vaccine, phác đồ, sản phẩm, lô và mũi tiêm chèn bằng SQL
 * (chưa có endpoint ghi mũi tiêm). Dọn theo hai pha vì FK {@code RESTRICT}: {@link #deleteDoses()} trước khi bên gọi xóa
 * Order / Visit, {@link #deleteCatalog()} sau khi dòng Order trỏ tới sản phẩm đã xóa và trước khi xóa chi nhánh.
 */
final class VaccinationItSupport {

    private final JdbcTemplate jdbc;
    private final List<Long> doses = new ArrayList<>();
    private final List<Long> lots = new ArrayList<>();
    private final List<Long> products = new ArrayList<>();
    private final List<Long> protocols = new ArrayList<>();
    private final List<Long> types = new ArrayList<>();
    private final List<Long> items = new ArrayList<>();
    private final List<Long> ownedOrders = new ArrayList<>();
    private Long category;

    VaccinationItSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long vaccineType(String species, String name) {
        long id = jdbc.queryForObject("""
                INSERT INTO vaccine_types (name, species) VALUES (?, ?) RETURNING id""", Long.class,
                name + " " + UUID.randomUUID().toString().substring(0, 8), species);
        types.add(id);
        return id;
    }

    long protocol(String species, long typeId, int dose, int intervalDays, boolean requiredForBoarding) {
        long id = jdbc.queryForObject("""
                INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks,
                        required_for_boarding) VALUES (?, ?, ?, ?, 6, ?) RETURNING id""", Long.class, species, typeId,
                dose, intervalDays, requiredForBoarding);
        protocols.add(id);
        return id;
    }

    /** Dòng phác đồ có tuổi tối thiểu và cờ {@code is_active} tường minh (visit-v1 #18, docs/adr/0073). */
    long protocolDose(String species, long typeId, int dose, int minAgeWeeks, boolean active) {
        long id = jdbc.queryForObject("""
                INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks,
                        is_active) VALUES (?, ?, ?, 21, ?, ?) RETURNING id""", Long.class, species, typeId, dose,
                minAgeWeeks, active);
        protocols.add(id);
        return id;
    }

    long product(long typeId, String name, boolean active) {
        if (category == null) {
            category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                    "Vaccine IT " + UUID.randomUUID());
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, vaccine_type_id, unit, price,
                        is_active) VALUES (?, ?, ?, 'VACCINE', true, ?, 'liều', 150000, ?) RETURNING id""", Long.class,
                category, "VAC-" + suffix, name + " " + suffix, typeId, active);
        products.add(id);
        return id;
    }

    long lot(long branchId, long productId) {
        long item = jdbc.queryForObject("""
                INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?)
                ON CONFLICT (branch_id, product_id) DO UPDATE SET min_quantity = inventory_items.min_quantity
                RETURNING id""", Long.class, branchId, productId);
        items.add(item);
        long lot = jdbc.queryForObject("""
                INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
                VALUES (?, ?, now()::date + 365, 10, false) RETURNING id""", Long.class, item,
                "LOT-" + UUID.randomUUID().toString().substring(0, 8));
        lots.add(lot);
        return lot;
    }

    /** Lô có hạn dùng và số lượng tường minh (lô đã hết hạn, lô hết hàng). */
    long lot(long branchId, long productId, LocalDate expiry, int quantity) {
        long item = jdbc.queryForObject("""
                INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?)
                ON CONFLICT (branch_id, product_id) DO UPDATE SET min_quantity = inventory_items.min_quantity
                RETURNING id""", Long.class, branchId, productId);
        items.add(item);
        long lot = jdbc.queryForObject("""
                INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
                VALUES (?, ?, ?, ?, false) RETURNING id""", Long.class, item,
                "LOT-" + UUID.randomUUID().toString().substring(0, 8), expiry, quantity);
        lots.add(lot);
        return lot;
    }

    /**
     * Order {@code VISIT} của lượt: dùng Order sẵn có (check-in qua API đã mở), thiếu thì chèn một Order {@code OPEN} và
     * ghi nhận để {@link #deleteDoses()} xóa cùng dòng của nó.
     */
    long orderOf(long visitId, long actor) {
        List<Long> found = jdbc.queryForList("SELECT id FROM orders WHERE visit_id = ?", Long.class, visitId);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                SELECT ?, 'VISIT', customer_id, branch_id, id, 'OPEN', ? FROM visits WHERE id = ? RETURNING id""",
                Long.class, "OD-IT-" + UUID.randomUUID().toString().substring(0, 12), actor, visitId);
        ownedOrders.add(id);
        return id;
    }

    /**
     * Một mũi tiêm của {@code visitId} (thú, chi nhánh lấy từ lượt) kèm dòng Order VACCINE 1–1 đúng như T28 sẽ ghi.
     * {@code doseNumber} và {@code nextDue} là giá trị đã chụp, độc lập với phác đồ hiện tại.
     */
    long dose(long visitId, long typeId, long protocolId, int doseNumber, long productId, long lotId,
            LocalDate administeredOn, LocalDate nextDue, long recordedBy, Instant supersededAt) {
        long order = orderOf(visitId, recordedBy);
        long line = jdbc.queryForObject("""
                INSERT INTO order_lines (order_id, line_type, product_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'VACCINE', ?, 'Vaccine IT', 1, 150000, 150000, ?, ?)
                RETURNING id""", Long.class, order, productId, recordedBy, recordedBy);
        long id = jdbc.queryForObject("""
                INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                        dose_number, product_id, stock_lot_id, order_line_id, next_due_date, superseded_at, recorded_by)
                SELECT pet_id, ?, id, branch_id, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM visits WHERE id = ? RETURNING id""",
                Long.class, typeId, administeredOn, protocolId, doseNumber, productId, lotId, line, nextDue,
                supersededAt == null ? null : java.sql.Timestamp.from(supersededAt), recordedBy, visitId);
        doses.add(id);
        return id;
    }

    /** Pha 1: mũi tiêm (trỏ tới dòng Order, lô, sản phẩm). Phải chạy trước khi xóa Order / Visit. */
    void deleteDoses() {
        for (long id : doses) {
            jdbc.update("DELETE FROM vaccinations WHERE id = ?", id);
        }
        doses.clear();
        for (long order : ownedOrders) {
            jdbc.update("DELETE FROM order_lines WHERE order_id = ?", order);
            jdbc.update("DELETE FROM orders WHERE id = ?", order);
        }
        ownedOrders.clear();
    }

    /** Pha 2: lô, tồn, sản phẩm, phác đồ, loại vaccine. Phải chạy sau khi dòng Order trỏ tới sản phẩm đã bị xóa. */
    void deleteCatalog() {
        for (long id : lots) {
            jdbc.update("DELETE FROM stock_lots WHERE id = ?", id);
        }
        for (long id : items) {
            jdbc.update("DELETE FROM inventory_items WHERE id = ?", id);
        }
        for (long id : products) {
            jdbc.update("DELETE FROM products WHERE id = ?", id);
        }
        for (long id : protocols) {
            jdbc.update("DELETE FROM vaccination_protocols WHERE id = ?", id);
        }
        for (long id : types) {
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id);
        }
        if (category != null) {
            jdbc.update("DELETE FROM product_categories WHERE id = ?", category);
        }
        lots.clear();
        items.clear();
        products.clear();
        protocols.clear();
        types.clear();
        category = null;
    }
}
