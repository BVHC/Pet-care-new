package com.petcare.module.customer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dữ liệu SQL cho IT của thú cưng (T17, docs/adr/0041): thú, cân nặng và các dòng "giao dịch" của module khác
 * (visit, lịch hẹn, đặt chỗ, Order, feedback, bệnh án, mũi tiêm) mà guard của customer cần đọc. Module visit / appointment /
 * boarding / sales chưa có entity, và một mũi tiêm thật kéo theo cả chuỗi lô hàng, phác đồ, dòng Order — nên các dòng
 * giao dịch chèn với {@code session_replication_role = replica} (bỏ kiểm FK tới bảng chưa có dữ liệu như {@code branches},
 * {@code services}; CHECK vẫn chạy). Mọi hàng đều gắn với một khách / thú do test tạo, nên {@link #cleanup()} xóa được
 * theo {@code customer_id} / {@code pet_id} mà không để lại gì cho IT khác.
 */
final class PetFixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<Long> customers = new ArrayList<>();
    private Long boardingBranchId;

    PetFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Ghi nhận khách để {@link #cleanup()} dọn (khách do test tạo bằng SQL). */
    long track(long customerId) {
        customers.add(customerId);
        return customerId;
    }

    long pet(long customerId, String name, String species) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, ?, ?, 'MALE') RETURNING id
                """, Long.class, customerId, name, species);
    }

    void weight(long petId, String kg, String source, String measuredAt) {
        jdbc.update("""
                INSERT INTO weight_records (pet_id, weight_kg, source, measured_at)
                VALUES (?, ?::numeric, ?, ?::timestamptz)
                """, petId, kg, source, measuredAt);
    }

    long visit(long customerId, long petId, String status) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                                    queue_sort_key, checked_in_by, checked_in_at, assignee_id)
                VALUES (?, 999999, ?, ?, 'MEDICAL', ?, 'WALK_IN', now(), 999999, now(),
                        CASE WHEN ? IN ('WAITING', 'CANCELLED') THEN NULL ELSE 999999 END)
                RETURNING id
                """, Long.class, code("V"), customerId, petId, status, status));
    }

    void medicalRecord(long visitId) {
        replica(() -> jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?)", visitId));
    }

    void vaccination(long petId, long visitId) {
        replica(() -> jdbc.update("""
                INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                                          dose_number, product_id, stock_lot_id, order_line_id, next_due_date,
                                          recorded_by)
                VALUES (?, 999999, ?, 999999, current_date, 999999, 1, 999999, 999999, 999999, current_date, 999999)
                """, petId, visitId));
    }

    long appointment(long customerId, long petId, String status) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                                          slot_start, status, channel, booked_by, cancel_source, cancelled_at)
                SELECT ?, ?, ?, 999999, 999999, 'MEDICAL', current_date + 30, '09:00', ?, 'COUNTER', 999999,
                       CASE WHEN ? = 'CANCELLED' THEN 'STAFF' END,
                       CASE WHEN ? = 'CANCELLED' THEN now() END
                RETURNING id
                """, Long.class, code("A"), customerId, petId, status, status, status));
    }

    /**
     * Chi nhánh thật cho đặt chỗ lưu trú: listener hủy do phòng khám của boarding (T31, docs/adr/0065) đọc tên chi nhánh
     * để soạn thông báo, nên đặt chỗ {@code BOOKED} không còn trỏ được tới chi nhánh giả 999999. Tạo lười, xóa ở
     * {@link #cleanup()}.
     */
    private long boardingBranch() {
        if (boardingBranchId == null) {
            boardingBranchId = jdbc.queryForObject("""
                    INSERT INTO branches (name, address, phone, latitude, longitude, status)
                    VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'DRAFT') RETURNING id
                    """, Long.class, "PetFixtures " + UUID.randomUUID());
        }
        return boardingBranchId;
    }

    /** Mỗi thú chỉ một đặt chỗ đang hiệu lực tại một thời điểm (EXCLUDE chồng ngày): truyền {@code offsetDays} khác nhau. */
    long booking(long customerId, long petId, String status, int offsetDays) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                                               check_out_date, nightly_price, status, channel, booked_by, kennel_id,
                                               end_reason, cancel_source)
                SELECT ?, ?, ?, ?, 999999, current_date + ?, current_date + ? + 2, 100000, ?, 'COUNTER', 999999,
                       CASE WHEN ? IN ('CHECKED_IN', 'OVERDUE', 'CHECKED_OUT') THEN ?::bigint END,
                       CASE WHEN ? = 'CHECKED_OUT' THEN 'RETURNED' END,
                       CASE WHEN ? = 'CANCELLED' THEN 'STAFF' END
                RETURNING id
                """, Long.class, code("B"), customerId, petId, boardingBranch(), offsetDays, offsetDays, status, status,
                SEQ.incrementAndGet(), status, status));
    }

    long orderForVisit(long customerId, long visitId, String status) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                VALUES (?, 'VISIT', ?, 999999, ?, ?, 999999) RETURNING id
                """, Long.class, code("O"), customerId, visitId, status));
    }

    long orderForBooking(long customerId, long bookingId, String status) {
        return replica(() -> jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, boarding_booking_id, status, created_by)
                VALUES (?, 'BOARDING', ?, 999999, ?, ?, 999999) RETURNING id
                """, Long.class, code("O"), customerId, bookingId, status));
    }

    void feedback(long customerId) {
        replica(() -> jdbc.update("""
                INSERT INTO feedbacks (customer_id, topic, content, status)
                VALUES (?, 'WEBSITE', 'Nội dung phản hồi đủ dài', 'NEW')
                """, customerId));
    }

    BigDecimal currentWeight(long petId) {
        return jdbc.queryForObject("""
                SELECT weight_kg FROM weight_records WHERE pet_id = ? ORDER BY measured_at DESC, id DESC LIMIT 1
                """, BigDecimal.class, petId);
    }

    /** Xóa mọi thứ gắn với các khách đã {@link #track}: con trước cha, bỏ kiểm FK vì có tham chiếu giả. */
    void cleanup() {
        if (customers.isEmpty()) {
            return;
        }
        Object[] ids = customers.toArray();
        String in = String.join(", ", Collections.nCopies(ids.length, "?"));
        String petsOf = "SELECT id FROM pets WHERE customer_id IN (" + in + ")";
        replica(() -> {
            jdbc.update("DELETE FROM orders WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM vaccinations WHERE pet_id IN (" + petsOf + ")", ids);
            jdbc.update("DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE customer_id IN ("
                    + in + "))", ids);
            jdbc.update("DELETE FROM visits WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM appointments WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM notification_outbox WHERE payload ->> 'ma_dat_cho' IN "
                    + "(SELECT code FROM boarding_bookings WHERE customer_id IN (" + in + "))", ids);
            jdbc.update("DELETE FROM boarding_bookings WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM feedbacks WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM weight_records WHERE pet_id IN (" + petsOf + ")", ids);
            jdbc.update("DELETE FROM pets WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM addresses WHERE customer_id IN (" + in + ")", ids);
            jdbc.update("DELETE FROM otp_tokens WHERE customer_id IN (" + in + ")", ids);
            return jdbc.update("DELETE FROM customers WHERE id IN (" + in + ")", ids);
        });
        customers.clear();
        if (boardingBranchId != null) {
            jdbc.update("DELETE FROM branches WHERE id = ?", boardingBranchId);
            boardingBranchId = null;
        }
    }

    private <T> T replica(Supplier<T> action) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            return action.get();
        });
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
