package com.petcare.module.care;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixture cho IT của Care Task (T39 phần 0, docs/adr/0079): chi nhánh, khách, thú là dòng thật (task trỏ tới chúng bằng
 * FK {@code RESTRICT}); dòng nguồn (mũi tiêm, bệnh án + Visit, đặt chỗ) chèn với
 * {@code session_replication_role = replica} để bỏ kiểm FK tới các bảng khác (khuôn {@code PetFixtures}) — task chỉ cần
 * dòng nguồn tồn tại. Dọn con trước cha ở {@link #cleanUp()}.
 */
public final class CareTaskItSupport {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> customers = new ArrayList<>();
    private final List<Long> pets = new ArrayList<>();
    private final List<Long> visits = new ArrayList<>();
    private final List<Long> extraTasks = new ArrayList<>();
    private final List<Long> vaccineTypes = new ArrayList<>();

    public CareTaskItSupport(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public long branch() {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'DRAFT') RETURNING id""", Long.class,
                "CareTaskIT " + UUID.randomUUID());
        branches.add(id);
        return id;
    }

    public long customer() {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES (?, '0912345678', 'COUNTER')
                RETURNING id""", Long.class, "Khách " + UUID.randomUUID());
        customers.add(id);
        return id;
    }

    public long pet(long customerId) {
        long id = jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Milu', 'DOG', 'MALE') RETURNING id""",
                Long.class, customerId);
        pets.add(id);
        return id;
    }

    /** Mũi tiêm của thú (tham chiếu tới Visit, phác đồ, lô… là giả). */
    public long vaccination(long petId) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                                          dose_number, product_id, stock_lot_id, order_line_id, next_due_date, recorded_by)
                VALUES (?, 999999, 999999, 999999, current_date, 999999, 1, 999999, 999999, ?, current_date, 999999)
                RETURNING id""", Long.class, petId, SEQ.incrementAndGet()));
    }

    /** Loại vaccine thật (tên duy nhất theo từng lần gọi) để kiểm {@code context} của việc gọi điện. */
    public long vaccineType(String name) {
        long id = jdbc.queryForObject("""
                INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id""", Long.class,
                name + " " + UUID.randomUUID());
        vaccineTypes.add(id);
        return id;
    }

    /** Mũi tiêm thuộc một loại vaccine thật; các tham chiếu còn lại là giả như {@link #vaccination(long)}. */
    public long vaccination(long petId, long vaccineTypeId) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                                          dose_number, product_id, stock_lot_id, order_line_id, next_due_date, recorded_by)
                VALUES (?, ?, 999999, 999999, current_date, 999999, 1, 999999, 999999, ?, current_date, 999999)
                RETURNING id""", Long.class, petId, vaccineTypeId, SEQ.incrementAndGet()));
    }

    /** Dòng nhận thú của đặt chỗ: nguồn của {@code emergencyPhone} mà PICKUP_OVERDUE gọi (BR-TB-05). */
    public void checkIn(long bookingId, String emergencyPhone) {
        replica(() -> jdbc.update("""
                INSERT INTO boarding_check_ins (boarding_booking_id, weight_record_id, health_condition, emergency_phone,
                                                received_by, received_at)
                VALUES (?, ?, 'Khỏe', ?, 999999, now())""", bookingId, SEQ.incrementAndGet(), emergencyPhone));
    }

    /** Bệnh án (khóa là id Visit) của một Visit giả lập của thú; trả {@code visit_id}. */
    public long medicalRecord(long branchId, long customerId, long petId) {
        long visit = replica(() -> {
            long id = jdbc.queryForObject("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                                        queue_sort_key, checked_in_by, checked_in_at, assignee_id)
                    VALUES (?, ?, ?, ?, 'MEDICAL', 'IN_PROGRESS', 'WALK_IN', now(), 999999, now(), 999999)
                    RETURNING id""", Long.class, "VC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    branchId, customerId, petId);
            jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?)", id);
            return id;
        });
        visits.add(visit);
        return visit;
    }

    /** Đặt chỗ {@code BOOKED}; mỗi thú một đặt chỗ đang hiệu lực (EXCLUDE chồng ngày): truyền {@code offsetDays} khác nhau. */
    public long booking(long branchId, long customerId, long petId, int offsetDays) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                                               check_out_date, nightly_price, status, channel, booked_by)
                VALUES (?, ?, ?, ?, 999999, current_date + ?, current_date + ? + 2, 100000, 'BOOKED', 'COUNTER', 999999)
                RETURNING id""", Long.class, "BC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                customerId, petId, branchId, offsetDays, offsetDays));
    }

    /**
     * Task chèn thẳng bằng SQL với trạng thái cho trước, thỏa bất biến của {@link #invariantViolations}: {@code DONE} có
     * kết quả, ghi chú và người xử lý; {@code CANCELLED} có lý do (do lễ tân hủy) và người xử lý.
     */
    public long task(String type, long sourceId, long branchId, long customerId, long petId, String status) {
        return task(type, sourceId, branchId, customerId, petId, status, null);
    }

    /** Như trên với hạn gọi cho trước ({@code null} = {@code current_date} của DB). */
    public long task(String type, long sourceId, long branchId, long customerId, long petId, String status,
            LocalDate dueDate) {
        String column = switch (type) {
            case "VACCINE_DUE", "VACCINE_OVERDUE" -> "vaccination_id";
            case "FOLLOW_UP_DUE" -> "medical_record_id";
            case "PICKUP_OVERDUE" -> "boarding_booking_id";
            default -> throw new IllegalArgumentException(type);
        };
        long id = replica(() -> jdbc.queryForObject("""
                INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, %s, due_date, status, result, note,
                                        cancel_reason, handled_by, handled_at)
                VALUES (?, ?, ?, ?, ?, coalesce(?::date, current_date), ?,
                        CASE WHEN ? = 'DONE' THEN 'REACHED' END,
                        CASE WHEN ? = 'DONE' THEN 'Đã gọi' END,
                        CASE WHEN ? = 'CANCELLED' THEN 'Lễ tân hủy' END,
                        CASE WHEN ? = 'OPEN' THEN NULL ELSE 999999 END,
                        CASE WHEN ? = 'OPEN' THEN NULL ELSE now() END)
                RETURNING id""".formatted(column), Long.class, branchId, customerId, petId, type, sourceId,
                dueDate == null ? null : dueDate.toString(), status, status, status, status, status, status));
        extraTasks.add(id);
        return id;
    }

    /**
     * Bất biến giữa các cột theo trạng thái mà DB chưa giữ (D049, docs/adr/0079): id các task của thú vi phạm. Phải rỗng
     * sau mọi ca.
     */
    public List<Long> invariantViolations() {
        if (pets.isEmpty()) {
            return List.of();
        }
        String in = String.join(", ", Collections.nCopies(pets.size(), "?"));
        return jdbc.queryForList("""
                SELECT id FROM care_tasks WHERE pet_id IN (%s) AND NOT (
                     (status = 'OPEN' AND result IS NULL AND note IS NULL AND cancel_reason IS NULL
                          AND handled_by IS NULL AND handled_at IS NULL)
                  OR (status = 'CANCELLED' AND result IS NULL AND cancel_reason IS NOT NULL AND handled_at IS NOT NULL)
                  OR (status = 'DONE' AND result IS NOT NULL AND cancel_reason IS NULL AND handled_by IS NOT NULL
                          AND handled_at IS NOT NULL))
                ORDER BY id""".formatted(in), Long.class, pets.toArray());
    }

    public long countTasks() {
        return count("care_tasks", "pet_id", pets);
    }

    private long count(String table, String column, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        String in = String.join(", ", Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " IN (" + in + ")",
                Long.class, ids.toArray());
    }

    /** Con trước cha: task, mũi tiêm, đặt chỗ, bệnh án, Visit, thú, khách, chi nhánh. */
    public void cleanUp() {
        replica(() -> {
            for (Long pet : pets) {
                jdbc.update("DELETE FROM care_tasks WHERE pet_id = ?", pet);
                jdbc.update("DELETE FROM vaccinations WHERE pet_id = ?", pet);
                jdbc.update("""
                        DELETE FROM boarding_check_ins
                        WHERE boarding_booking_id IN (SELECT id FROM boarding_bookings WHERE pet_id = ?)""", pet);
                jdbc.update("DELETE FROM boarding_bookings WHERE pet_id = ?", pet);
            }
            for (Long task : extraTasks) {
                jdbc.update("DELETE FROM care_tasks WHERE id = ?", task);
            }
            for (Long visit : visits) {
                jdbc.update("DELETE FROM medical_records WHERE visit_id = ?", visit);
                jdbc.update("DELETE FROM visits WHERE id = ?", visit);
            }
            for (Long pet : pets) {
                jdbc.update("DELETE FROM pets WHERE id = ?", pet);
            }
            for (Long type : vaccineTypes) {
                jdbc.update("DELETE FROM vaccine_types WHERE id = ?", type);
            }
            for (Long customer : customers) {
                jdbc.update("DELETE FROM customers WHERE id = ?", customer);
            }
            for (Long branch : branches) {
                jdbc.update("DELETE FROM branches WHERE id = ?", branch);
            }
            return null;
        });
        pets.clear();
        customers.clear();
        vaccineTypes.clear();
        branches.clear();
        visits.clear();
        extraTasks.clear();
    }

    /** Chạy trong một transaction bỏ kiểm FK (trigger {@code replica}); CHECK và unique vẫn áp dụng. */
    public <T> T replica(Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            return action.get();
        });
    }
}
