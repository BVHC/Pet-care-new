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
    private final List<Long> prescriptionItems = new ArrayList<>();
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

    /** Thuốc (không phải vaccine, không gắn loại vaccine) — #19 phải từ chối (docs/adr/0074). */
    long drugProduct(String name) {
        if (category == null) {
            category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                    "Vaccine IT " + UUID.randomUUID());
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'DRUG', true, 'viên', 20000) RETURNING id""", Long.class, category, "DRG-" + suffix,
                name + " " + suffix);
        products.add(id);
        return id;
    }

    /** Thuốc kê đơn (BR-SP-01): {@code DRUG}, {@code is_prescription}, bắt buộc quản lý hạn dùng (BR-SP-05). */
    long prescriptionDrug(String name) {
        if (category == null) {
            category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                    "Vaccine IT " + UUID.randomUUID());
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, is_prescription, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'DRUG', true, true, 'viên', 30000) RETURNING id""", Long.class, category,
                "RX-" + suffix, name + " " + suffix);
        products.add(id);
        return id;
    }

    /** Bệnh án rỗng của lượt nếu chưa có: FK của {@code prescription_items.medical_record_id} (T38 phần 0, docs/adr/0076). */
    void ensureRecord(long visitId) {
        jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?) ON CONFLICT (visit_id) DO NOTHING", visitId);
    }

    /** Dòng {@code DRUG} trong Order {@code VISIT} của lượt — thứ mà {@code VisitOrderApi.addDrugLine} sẽ sinh ở phần 1. */
    long drugLine(long visitId, long productId, int quantity, long actor) {
        long order = orderOf(visitId, actor);
        return jdbc.queryForObject("""
                INSERT INTO order_lines (order_id, line_type, product_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id) VALUES (?, 'DRUG', ?, 'Thuốc IT', ?, 30000, ? * 30000, ?, ?)
                RETURNING id""", Long.class, order, productId, quantity, quantity, actor, actor);
    }

    /**
     * Một dòng đơn thuốc của bệnh án {@code visitId} (tạo bệnh án rỗng nếu chưa có). {@code orderLineId} null là dòng mua
     * ngoài lúc kê (BR-KB-03: {@code OUT_OF_STOCK_AT_PRESCRIBE}); khác null là dòng có Order. Xóa ở {@link #deleteDoses()}.
     */
    long prescriptionItem(long visitId, long productId, int quantity, String dosage, Long orderLineId) {
        ensureRecord(visitId);
        long id = jdbc.queryForObject("""
                INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                        is_external_purchase, order_line_id, external_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, visitId, productId, quantity, dosage,
                orderLineId == null, orderLineId, orderLineId == null ? "OUT_OF_STOCK_AT_PRESCRIBE" : null);
        prescriptionItems.add(id);
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

    /**
     * Care Task của một mũi tiêm (T28 phần 2, docs/adr/0074); {@code DONE} cần kết quả (CHECK {@code done_result}).
     * Xóa ở {@link #deleteDosesOfPet}.
     */
    long careTask(long vaccinationId, long petId, String taskType, String status) {
        // V22 (docs/adr/0081): DONE có ghi chú + người xử lý + giờ; CANCELLED có lý do + giờ (người xử lý NULL = hệ thống)
        return jdbc.queryForObject("""
                INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, vaccination_id, due_date, status, result,
                                        note, cancel_reason, handled_by, handled_at)
                SELECT v.branch_id, p.customer_id, p.id, ?, ?, now()::date, ?, CASE WHEN ? = 'DONE' THEN 'REACHED' END,
                       CASE WHEN ? = 'DONE' THEN 'đã gọi' END, CASE WHEN ? = 'CANCELLED' THEN 'hủy trong IT' END,
                       CASE WHEN ? = 'DONE' THEN v.recorded_by END, CASE WHEN ? <> 'OPEN' THEN now() END
                FROM vaccinations v JOIN pets p ON p.id = v.pet_id WHERE v.id = ? AND p.id = ? RETURNING id""", Long.class,
                taskType, vaccinationId, status, status, status, status, status, status, vaccinationId, petId);
    }

    /**
     * Mũi tiêm và Care Task của thú, kể cả mũi ghi qua API (không có trong {@link #doses}); chạy trước
     * {@link #deleteDoses()}: mũi trỏ tới dòng Order, Care Task trỏ tới mũi (FK {@code RESTRICT}).
     */
    void deleteDosesOfPet(long petId) {
        jdbc.update("DELETE FROM care_tasks WHERE pet_id = ?", petId);
        jdbc.update("DELETE FROM vaccinations WHERE pet_id = ?", petId);
    }

    /**
     * Đơn thuốc và bệnh án của mọi lượt của thú, kể cả dòng kê qua API #16 (không có trong {@link #prescriptionItems});
     * chạy trước {@link #deleteDoses()}: dòng đơn thuốc trỏ tới dòng Order và bệnh án (FK {@code RESTRICT}).
     */
    void deletePrescriptionsOfPet(long petId) {
        jdbc.update("DELETE FROM prescription_items WHERE medical_record_id IN (SELECT id FROM visits WHERE pet_id = ?)",
                petId);
        jdbc.update("DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ?)", petId);
    }

    /**
     * Pha 1: đơn thuốc (trỏ tới dòng Order, bệnh án, sản phẩm), rồi mũi tiêm (trỏ tới dòng Order, lô, sản phẩm). Phải chạy
     * trước khi xóa Order / bệnh án / Visit.
     */
    void deleteDoses() {
        for (long id : prescriptionItems) {
            jdbc.update("DELETE FROM prescription_items WHERE id = ?", id);
        }
        prescriptionItems.clear();
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
            // movement ghi khi tiêm qua API (docs/adr/0074) trỏ tới lô, FK RESTRICT
            jdbc.update("DELETE FROM stock_movements WHERE stock_lot_id = ?", id);
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
