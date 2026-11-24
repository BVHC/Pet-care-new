package com.petcare.module.inpatient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.petcare.module.appointment.AppointmentItSupport;

/**
 * Dữ liệu IT cho nội trú: chi nhánh, nhân viên, khách, thú lấy từ {@link AppointmentItSupport}; còn dịch vụ
 * {@code INPATIENT}, Visit {@code IN_PROGRESS}, Order, dòng Order, đơn thuốc, loại chuồng, chuồng, đặt chỗ lưu trú chèn
 * thẳng bằng SQL ở đúng trạng thái cần. {@link #cleanUp()} xóa mọi dòng đã ghi nhận <b>trước</b> rồi mới gọi
 * {@code cleanUp()} của {@code AppointmentItSupport}, vì mọi FK là {@code RESTRICT}. Không dùng lại
 * {@code VaccinationItSupport}: hàm của nó là package-private ở package {@code visit}.
 */
class InpatientItSupport {

    private final JdbcTemplate jdbc;
    private final AppointmentItSupport base;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> pets = new ArrayList<>();
    private final List<Long> services = new ArrayList<>();
    private final List<Long> products = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();
    private final List<Long> kennelTypes = new ArrayList<>();

    InpatientItSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.base = new AppointmentItSupport(jdbc);
    }

    long branch() {
        long id = base.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    long staff(String role, Long branchId) {
        return base.staff(role, branchId);
    }

    long customer() {
        return base.counterCustomer();
    }

    /** Hồ sơ tại quầy có email (hoặc {@code null}): khách nhận thông báo theo BR-TB-02 chỉ qua email. */
    long counterCustomer(String fullName, String email) {
        return base.counterCustomer(fullName, email);
    }

    /** Hồ sơ online gắn tài khoản khách {@code accountStatus} ({@code ACTIVE} / {@code PENDING}); trả {account, customer}. */
    long[] onlineCustomer(String fullName, String accountStatus) {
        long account = base.customerAccount("ob-" + UUID.randomUUID() + "@example.com", accountStatus);
        return new long[] {account, base.onlineCustomer(account, fullName)};
    }

    long pet(long customerId) {
        long id = base.pet(customerId);
        pets.add(id);
        return id;
    }

    /** Dịch vụ nội trú: nhóm MEDICAL, loại {@code INPATIENT} (V41), giá theo ngày. */
    long inpatientService(long dailyPrice) {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'INPATIENT', ?) RETURNING id""", Long.class, "Nội trú " + UUID.randomUUID(),
                dailyPrice);
        services.add(id);
        return id;
    }

    /** Bật / tắt dịch vụ tại chi nhánh ({@code branch_services}); xóa trước {@code services} trong {@link #cleanUp()}. */
    void enable(long branchId, long serviceId, boolean enabled) {
        base.branchService(branchId, serviceId, enabled);
    }

    /** Visit {@code IN_PROGRESS} do {@code vet} phụ trách (không qua hàng đợi), kèm Order {@code VISIT} {@code OPEN}. */
    long visit(long branchId, long customerId, long petId, long vet) {
        long id = jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', 'IN_PROGRESS', 'WALK_IN', now(), ?, ?, now(), now()) RETURNING id""",
                Long.class, "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customerId, petId, vet,
                vet);
        jdbc.update("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                VALUES (?, 'VISIT', ?, ?, ?, 'OPEN', ?)""", "OD-IT-" + UUID.randomUUID().toString().substring(0, 12),
                customerId, branchId, id, vet);
        return id;
    }

    /** Một dòng dịch vụ trong Order của Visit: thứ mà xuất viện sẽ thêm (BR-LT-14); mỗi ca đã xuất cần một dòng riêng. */
    long orderLine(long visitId, long serviceId, int quantity, long unitPrice, long actor) {
        return jdbc.queryForObject("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id)
                SELECT id, 'SERVICE', ?, 'Nội trú IT', ?, ?, ? * ?, ?, ? FROM orders WHERE visit_id = ? RETURNING id""",
                Long.class, serviceId, quantity, unitPrice, quantity, unitPrice, actor, actor, visitId);
    }

    /** Dòng đơn thuốc (mua ngoài) của bệnh án {@code visitId}: thứ mà y lệnh {@code MEDICATION} liên kết tới. */
    long prescriptionItem(long visitId) {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "Nội trú IT " + UUID.randomUUID());
        categories.add(category);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long product = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, is_prescription, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'DRUG', true, true, 'viên', 30000) RETURNING id""", Long.class, category,
                "RX-" + suffix, "Thuốc nội trú " + suffix);
        products.add(product);
        jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?) ON CONFLICT (visit_id) DO NOTHING", visitId);
        return jdbc.queryForObject("""
                INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                        is_external_purchase, external_reason)
                VALUES (?, ?, 1, '2 lần / ngày', true, 'OUT_OF_STOCK_AT_PRESCRIBE') RETURNING id""", Long.class,
                visitId, product);
    }

    /**
     * Dòng đơn thuốc <b>của phòng khám</b> (không mua ngoài) của bệnh án {@code visitId}: có dòng Order {@code DRUG} gắn
     * vào Order của Visit (CHECK {@code is_external_purchase = (order_line_id IS NULL)}). Đây là dòng mà y lệnh
     * {@code MEDICATION} liên kết được (BR-LT-15, ADR-0149 mục 27).
     */
    long clinicPrescriptionItem(long visitId, long actor) {
        long product = drugProduct();
        jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?) ON CONFLICT (visit_id) DO NOTHING", visitId);
        long line = jdbc.queryForObject("""
                INSERT INTO order_lines (order_id, line_type, product_id, description, quantity, unit_price, line_total,
                        added_by, owner_account_id)
                SELECT id, 'DRUG', ?, 'Thuốc nội trú IT', 1, 30000, 30000, ?, ? FROM orders WHERE visit_id = ?
                RETURNING id""", Long.class, product, actor, actor, visitId);
        return jdbc.queryForObject("""
                INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                        is_external_purchase, order_line_id)
                VALUES (?, ?, 1, '2 lần / ngày', false, ?) RETURNING id""", Long.class, visitId, product, line);
    }

    private long drugProduct() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "Nội trú IT " + UUID.randomUUID());
        categories.add(category);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long product = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, is_prescription, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'DRUG', true, true, 'viên', 30000) RETURNING id""", Long.class, category,
                "RX-" + suffix, "Thuốc nội trú " + suffix);
        products.add(product);
        return product;
    }


    /** Chuồng {@code AVAILABLE} (loại chuồng và dịch vụ chuồng tạo kèm) của chi nhánh. */
    long kennel(long branchId) {
        long service = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, price) VALUES (?, 'BOARDING', 100000) RETURNING id""",
                Long.class, "Chuồng nội trú " + UUID.randomUUID());
        services.add(service);
        jdbc.update("INSERT INTO kennel_types (service_id, species, max_weight_kg) VALUES (?, 'DOG', 30)", service);
        kennelTypes.add(service);
        return jdbc.queryForObject("""
                INSERT INTO kennels (branch_id, kennel_type_id, code, status) VALUES (?, ?, ?, 'AVAILABLE')
                RETURNING id""", Long.class, branchId, service, "K" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Đặt chỗ lưu trú {@code CHECKED_IN} trong một chuồng {@code OCCUPIED}: nguồn của ca nhập từ lưu trú (đường b). */
    long checkedInBooking(long branchId, long customerId, long petId, long bookedBy) {
        long kennel = kennel(branchId);
        jdbc.update("UPDATE kennels SET status = 'OCCUPIED' WHERE id = ?", kennel);
        return jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, kennel_id,
                        check_in_date, check_out_date, actual_check_in_at, nightly_price, status, channel, booked_by)
                SELECT ?, ?, ?, ?, kennel_type_id, id, current_date, current_date + 3, now(), 100000, 'CHECKED_IN',
                       'COUNTER', ? FROM kennels WHERE id = ? RETURNING id""", Long.class,
                "LT-IT-" + UUID.randomUUID().toString().substring(0, 8), customerId, petId, branchId, bookedBy, kennel);
    }

    /**
     * Như {@link #checkedInBooking(long, long, long, long)} nhưng ngày nhận / trả theo đồng hồ của era (IT chạy với
     * {@code MutableClock} ở năm xa, trong khi {@code current_date} của DB là ngày thật): số đêm tính tiền của trả thú /
     * thú mất lấy từ ngày nhận này.
     */
    long checkedInBooking(long branchId, long customerId, long petId, long bookedBy, LocalDate checkIn,
            LocalDate checkOut) {
        long kennel = kennel(branchId);
        jdbc.update("UPDATE kennels SET status = 'OCCUPIED' WHERE id = ?", kennel);
        return jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, kennel_id,
                        check_in_date, check_out_date, actual_check_in_at, nightly_price, status, channel, booked_by)
                SELECT ?, ?, ?, ?, kennel_type_id, id, ?, ?, now(), 100000, 'CHECKED_IN', 'COUNTER', ?
                FROM kennels WHERE id = ? RETURNING id""", Long.class,
                "LT-IT-" + UUID.randomUUID().toString().substring(0, 8), customerId, petId, branchId, checkIn,
                checkOut, bookedBy, kennel);
    }

    /** Đặt chỗ {@code BOOKED} (chưa có chuồng) đúng loại chuồng của {@code kennelId}: nguồn của "nhận thú" (boarding #14). */
    long bookedBooking(long branchId, long customerId, long petId, long bookedBy, long kennelId, LocalDate checkIn,
            LocalDate checkOut) {
        return jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                        check_out_date, nightly_price, status, channel, booked_by)
                SELECT ?, ?, ?, ?, kennel_type_id, ?, ?, 100000, 'BOOKED', 'COUNTER', ?
                FROM kennels WHERE id = ? RETURNING id""", Long.class,
                "LT-IT-" + UUID.randomUUID().toString().substring(0, 8), customerId, petId, branchId, checkIn,
                checkOut, bookedBy, kennelId);
    }

    /** Mở cửa cả ngày, cả 7 ngày, từ năm 2000: trả thú / nhận thú chỉ trong giờ mở cửa (BR-LT-04). Xóa cùng chi nhánh. */
    void openAllDay(long branchId) {
        base.openingHours(branchId, LocalDate.of(2000, 1, 1), LocalTime.of(0, 0), LocalTime.of(23, 59), null, null);
    }

    /** Ca thu ngân {@code OPEN} của lễ tân (thu tiền Order lưu trú qua {@code POST /api/payments} cần ca). */
    long openShift(long branchId, long cashierId, LocalDate businessDate, Instant openedAt) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, ?, 'OPEN', ?) RETURNING id""", Long.class, branchId, cashierId, businessDate,
                Timestamp.from(openedAt));
    }

    /** Xóa theo thứ tự FK: dữ liệu nội trú, Order, bệnh án, Visit, đặt chỗ, chuồng, danh mục; rồi phần của base. */
    void cleanUp() {
        for (long pet : pets) {
            // N7: Order lưu trú và hồ sơ nhận thú (trả thú / thú mất / nhận thú qua API) trước đặt chỗ; cân nặng nhận thú sau đó.
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE boarding_booking_id IN
                        (SELECT id FROM boarding_bookings WHERE pet_id = ?))""", pet);
            jdbc.update("""
                    DELETE FROM orders WHERE boarding_booking_id IN (SELECT id FROM boarding_bookings WHERE pet_id = ?)""",
                    pet);
            jdbc.update("""
                    DELETE FROM boarding_check_ins WHERE boarding_booking_id IN
                        (SELECT id FROM boarding_bookings WHERE pet_id = ?)""", pet);
            jdbc.update("DELETE FROM weight_records WHERE pet_id = ?", pet);
            jdbc.update("""
                    DELETE FROM admission_observations
                    WHERE admission_id IN (SELECT id FROM admissions WHERE pet_id = ?)""", pet);
            jdbc.update("""
                    DELETE FROM admission_orders
                    WHERE admission_id IN (SELECT id FROM admissions WHERE pet_id = ?)""", pet);
            jdbc.update("DELETE FROM admissions WHERE pet_id = ?", pet);
            jdbc.update("""
                    DELETE FROM prescription_items
                    WHERE medical_record_id IN (SELECT id FROM visits WHERE pet_id = ?)""", pet);
            jdbc.update("""
                    DELETE FROM order_lines
                    WHERE order_id IN (SELECT id FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ?))""",
                    pet);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ?)", pet);
            jdbc.update("DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ?)", pet);
            jdbc.update("DELETE FROM visits WHERE pet_id = ?", pet);
            jdbc.update("DELETE FROM boarding_bookings WHERE pet_id = ?", pet);
        }
        for (long branch : branches) {
            jdbc.update("DELETE FROM payments WHERE branch_id = ?", branch);
            jdbc.update("DELETE FROM cashier_shifts WHERE branch_id = ?", branch);
            jdbc.update("DELETE FROM kennels WHERE branch_id = ?", branch);
        }
        for (long type : kennelTypes) {
            jdbc.update("DELETE FROM kennel_types WHERE service_id = ?", type);
        }
        for (long service : services) {
            jdbc.update("DELETE FROM branch_services WHERE service_id = ?", service);
            jdbc.update("DELETE FROM services WHERE id = ?", service);
        }
        for (long product : products) {
            jdbc.update("DELETE FROM products WHERE id = ?", product);
        }
        for (long category : categories) {
            jdbc.update("DELETE FROM product_categories WHERE id = ?", category);
        }
        pets.clear();
        branches.clear();
        services.clear();
        kennelTypes.clear();
        products.clear();
        categories.clear();
        base.cleanUp();
    }
}
