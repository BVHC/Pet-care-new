package com.petcare.module;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dữ liệu SQL cho IT của các interface đọc dành cho module {@code ai} (AI-0, I-1…I-8, I-10). Các dòng "giao dịch" chèn với
 * {@code session_replication_role = replica} (bỏ kiểm FK tới khách, tài khoản, chi nhánh giả; CHECK vẫn chạy), nên một Order
 * hay một mũi tiêm không kéo theo cả chuỗi chứng từ. Mọi id sinh từ {@link #next()} nên không đụng dữ liệu của IT khác; chỉ
 * những dòng do fixture tạo mới bị {@link #cleanup()} xóa (ngược thứ tự tạo).
 */
public final class AiReadFixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L + 2_000_000_000L);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<Runnable> cleanups = new ArrayList<>();

    public AiReadFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Id giả duy nhất (khách, chi nhánh, loại vaccine, sản phẩm, loại chuồng không cần dòng thật). */
    public static long next() {
        return SEQ.incrementAndGet();
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private long insert(String table, String sql, Object... args) {
        long id = replica(() -> jdbc.queryForObject(sql, Long.class, args));
        cleanups.add(() -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id));
        return id;
    }

    // ---------------------------------------------------------------- customer

    public long pet(long customerId, String name, String species, LocalDate birthDate, LocalDate deceasedOn,
            String breed) {
        return insert("pets", """
                INSERT INTO pets (customer_id, name, species, sex, breed, birth_date, deceased_on)
                VALUES (?, ?, ?, 'MALE', ?, ?, ?) RETURNING id
                """, customerId, name, species, breed, birthDate, deceasedOn);
    }

    public long pet(long customerId, String species, LocalDate birthDate) {
        return pet(customerId, "AI-" + UUID.randomUUID().toString().substring(0, 8), species, birthDate, null, null);
    }

    public void weight(long petId, String kg, String measuredAt) {
        insert("weight_records", """
                INSERT INTO weight_records (pet_id, weight_kg, source, measured_at)
                VALUES (?, ?::numeric, 'VET', ?::timestamptz) RETURNING id
                """, petId, kg, measuredAt);
    }

    public void movePet(long petId, long toCustomerId) {
        replica(() -> jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", toCustomerId, petId));
    }

    // ---------------------------------------------------------------- visit / vaccination

    public long vaccination(long petId, long vaccineTypeId, int dose, LocalDate administeredOn, LocalDate nextDue,
            boolean superseded) {
        return insert("vaccinations", """
                INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                                          dose_number, product_id, stock_lot_id, order_line_id, next_due_date,
                                          superseded_at, recorded_by)
                VALUES (?, ?, 999999, 999999, ?, 999999, ?, 999999, 999999, ?, ?,
                        CASE WHEN ? THEN now() END, 999999) RETURNING id
                """, petId, vaccineTypeId, administeredOn, dose, next(), nextDue, superseded);
    }

    public long visit(long customerId, long petId, String status, Instant completedAt) {
        return insert("visits", """
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                                    queue_sort_key, checked_in_by, checked_in_at, assignee_id, completed_at)
                VALUES (?, 999999, ?, ?, 'GROOMING', ?, 'WALK_IN', now(), 999999, now(),
                        CASE WHEN ? IN ('WAITING', 'CANCELLED') THEN NULL ELSE 999999 END, ?) RETURNING id
                """, code("V"), customerId, petId, status, status,
                completedAt == null ? null : java.sql.Timestamp.from(completedAt));
    }

    // ---------------------------------------------------------------- sales

    /** Order nguồn VISIT khi có {@code visitId}, còn lại RETAIL; {@code PAID} cần {@code payment_id} (CHECK). */
    public long order(long customerId, Long visitId, String status, Instant paidAt) {
        return insert("orders", """
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, payment_id, status, created_by, paid_at)
                VALUES (?, ?, ?, 999999, ?, CASE WHEN ? = 'PAID' THEN 999999::bigint END, ?, 999999, ?) RETURNING id
                """, code("O"), visitId == null ? "RETAIL" : "VISIT", customerId, visitId, status, status,
                paidAt == null ? null : java.sql.Timestamp.from(paidAt));
    }

    public void serviceLine(long orderId, long serviceId) {
        insert("order_lines", """
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                                         added_by, owner_account_id)
                VALUES (?, 'SERVICE', ?, 'Dịch vụ', 1, 100000, 100000, 999999, 999999) RETURNING id
                """, orderId, serviceId);
    }

    public void goodsLine(long orderId, long productId, int quantity) {
        insert("order_lines", """
                INSERT INTO order_lines (order_id, line_type, product_id, description, quantity, unit_price, line_total,
                                         added_by, owner_account_id)
                VALUES (?, 'GOODS', ?, 'Hàng', ?, 10000, ? * 10000, 999999, 999999) RETURNING id
                """, orderId, productId, quantity, quantity);
    }

    // ---------------------------------------------------------------- appointment / boarding

    public long appointment(long customerId, long petId, long serviceId, String status, LocalDate slotDate,
            LocalTime slotStart) {
        return insert("appointments", """
                INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                                          slot_start, status, channel, booked_by, cancel_source, cancelled_at)
                SELECT ?, ?, ?, 999999, ?, 'MEDICAL', ?, ?, ?, 'COUNTER', 999999,
                       CASE WHEN ? = 'CANCELLED' THEN 'STAFF' END, CASE WHEN ? = 'CANCELLED' THEN now() END
                RETURNING id
                """, code("A"), customerId, petId, serviceId, slotDate, slotStart, status, status, status);
    }

    /** {@code actualCheckOutAt} chỉ có nghĩa với {@code CHECKED_OUT}; mỗi lần ở một khoảng ngày riêng cho một thú. */
    public long booking(long customerId, long petId, long kennelTypeId, String status, LocalDate checkIn,
            Instant actualCheckOutAt) {
        return insert("boarding_bookings", """
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                                               check_out_date, actual_check_out_at, nightly_price, status, channel,
                                               booked_by, kennel_id, end_reason, cancel_source)
                SELECT ?, ?, ?, 999999, ?, ?::date, ?::date + 2, ?, 100000, ?, 'COUNTER', 999999,
                       CASE WHEN ? IN ('CHECKED_IN', 'OVERDUE', 'CHECKED_OUT') THEN ?::bigint END,
                       CASE WHEN ? = 'CHECKED_OUT' THEN 'RETURNED' END,
                       CASE WHEN ? = 'CANCELLED' THEN 'STAFF' END
                RETURNING id
                """, code("B"), customerId, petId, kennelTypeId, checkIn, checkIn,
                actualCheckOutAt == null ? null : java.sql.Timestamp.from(actualCheckOutAt), status, status, next(),
                status, status);
    }

    // ---------------------------------------------------------------- catalog

    public long service(String group, boolean active) {
        return insert("services", """
                INSERT INTO services (name, service_group, medical_type, price, is_active)
                VALUES (?, ?, CASE WHEN ? = 'MEDICAL' THEN 'EXAM' END, 120000, ?) RETURNING id
                """, "AI-test " + UUID.randomUUID(), group, group, active);
    }

    public void kennelType(long serviceId, String species, String maxWeightKg) {
        replica(() -> jdbc.update("INSERT INTO kennel_types (service_id, species, max_weight_kg) VALUES (?, ?, ?::numeric)",
                serviceId, species, maxWeightKg));
        cleanups.add(() -> jdbc.update("DELETE FROM kennel_types WHERE service_id = ?", serviceId));
    }

    /** {@code VACCINE} cần {@code vaccine_type_id}; thuốc kê đơn phải là {@code DRUG} và quản lý hạn dùng (CHECK). */
    public long product(String type, boolean prescription, boolean active) {
        boolean tracksExpiry = prescription || "VACCINE".equals(type);
        return insert("products", """
                INSERT INTO products (category_id, sku, name, product_type, is_prescription, tracks_expiry,
                                      vaccine_type_id, unit, price, is_active)
                VALUES (999999, ?, ?, ?, ?, ?, CASE WHEN ? = 'VACCINE' THEN 999999::bigint END, 'cái', 50000, ?)
                RETURNING id
                """, "AI-" + UUID.randomUUID().toString().substring(0, 20), "AI-test " + UUID.randomUUID(), type,
                prescription, tracksExpiry, type, active);
    }

    public long protocol(String species, long vaccineTypeId, int dose, boolean active) {
        return insert("vaccination_protocols", """
                INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks,
                                                   required_for_boarding, is_active)
                VALUES (?, ?, ?, 30, 6, false, ?) RETURNING id
                """, species, vaccineTypeId, dose, active);
    }

    // ---------------------------------------------------------------- branch

    public long branch(String namePrefix, String status, boolean emergency) {
        return insert("branches", """
                INSERT INTO branches (name, address, phone, latitude, longitude, status, accepts_after_hours_emergency)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, ?, ?) RETURNING id
                """, namePrefix + UUID.randomUUID(), status, emergency);
    }

    public void branchService(long branchId, long serviceId, boolean enabled) {
        replica(() -> jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, ?)",
                branchId, serviceId, enabled));
        cleanups.add(() -> jdbc.update("DELETE FROM branch_services WHERE branch_id = ? AND service_id = ?", branchId,
                serviceId));
    }

    // ---------------------------------------------------------------- inventory

    /** Một lô của (chi nhánh, sản phẩm); {@code expiry} null = không hạn. */
    public void lot(long branchId, long productId, int quantity, LocalDate expiry) {
        long itemId = jdbc.query("SELECT id FROM inventory_items WHERE branch_id = ? AND product_id = ?",
                (rs, n) -> rs.getLong(1), branchId, productId).stream().findFirst().orElseGet(() ->
                insert("inventory_items", "INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?) RETURNING id",
                        branchId, productId));
        insert("stock_lots", """
                INSERT INTO stock_lots (inventory_item_id, lot_number, expiry_date, quantity, is_default)
                VALUES (?, ?, ?, ?, false) RETURNING id
                """, itemId, "L" + next(), expiry, quantity);
    }

    public void reservation(long branchId, long productId, int quantity, String status) {
        insert("stock_reservations", """
                INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, next(), branchId, productId, quantity, status);
    }

    // ---------------------------------------------------------------- cleanup

    /** Xóa mọi dòng đã tạo, ngược thứ tự tạo; bỏ kiểm FK vì có tham chiếu giả. */
    public void cleanup() {
        List<Runnable> reversed = new ArrayList<>(cleanups);
        java.util.Collections.reverse(reversed);
        replica(() -> {
            reversed.forEach(Runnable::run);
            return null;
        });
        cleanups.clear();
    }

    private <T> T replica(Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            return action.get();
        });
    }
}
