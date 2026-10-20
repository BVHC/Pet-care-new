package com.petcare.module.appointment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dữ liệu cho IT của lịch hẹn, chèn bằng SQL vì customer / catalog / branch chưa có (hoặc không cần) đường ghi chung
 * (docs/adr/0045, kế hoạch T16 phần 0 mục I5). Chi nhánh luôn {@code DRAFT} nếu không yêu cầu khác, tên / email UUID, nên
 * không đổi kết quả của danh sách công khai hay test khác. {@link #cleanUp()} xóa theo thứ tự FK, con trước:
 * {@code visits (theo lịch hẹn và theo thú) → appointments (theo id và theo thú — lịch đặt qua HTTP) → pets → booking_restrictions → customers → (dữ liệu con
 * của chi nhánh, sessions, staff_profiles) → services → branches → accounts}. Không ghi audit.
 */
public final class AppointmentItSupport {

    private final JdbcTemplate jdbc;
    private final Deque<long[]> created = new ArrayDeque<>();

    private static final int APPOINTMENT = 0;
    private static final int PET = 1;
    private static final int CUSTOMER = 2;
    private static final int SERVICE = 3;
    private static final int BRANCH = 4;
    private static final int ACCOUNT = 5;
    private static final String[] TABLES = {"appointments", "pets", "customers", "services", "branches", "accounts"};
    private static final String[] BRANCH_CHILDREN = {"branch_services", "opening_hours", "holidays", "slot_quotas",
            "branch_quota_defaults"};

    public AppointmentItSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long branch(String status) {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status, activated_at)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?, CASE WHEN ? = 'ACTIVE' THEN now() END)
                RETURNING id""", Long.class, "AppointmentIT " + UUID.randomUUID(), status, status);
        return track(BRANCH, id);
    }

    public long receptionist() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'x', 'RECEPTIONIST', 'ACTIVE') RETURNING id""",
                Long.class, "appt-" + UUID.randomUUID() + "@example.com");
        return track(ACCOUNT, id);
    }

    /** Hồ sơ tại quầy (BR-KH-01: bắt buộc SĐT, không cần tài khoản). */
    public long counterCustomer() {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES (?, '0912345678', 'COUNTER')
                RETURNING id""", Long.class, "Khách " + UUID.randomUUID());
        return track(CUSTOMER, id);
    }

    public long pet(long customerId) {
        long id = jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Milu', 'DOG', 'MALE') RETURNING id""",
                Long.class, customerId);
        return track(PET, id);
    }

    /** Dịch vụ nhóm {@code group}; nhóm MEDICAL bắt buộc có loại (BR-SP-06). */
    public long service(String group) {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, ?, CASE WHEN ? = 'MEDICAL' THEN 'EXAM' END, 150000) RETURNING id""",
                Long.class, "Dịch vụ " + UUID.randomUUID(), group, group);
        return track(SERVICE, id);
    }

    /** Ghi nhận id lịch hẹn chèn bằng SQL ở test để {@link #cleanUp()} xóa. */
    public long appointment(long id) {
        return track(APPOINTMENT, id);
    }

    /** Chèn một lịch hẹn và ghi nhận để xóa; mã ngẫu nhiên, không qua sequence V16. */
    public long appointment(long customerId, long petId, long branchId, long serviceId, String serviceGroup,
            LocalDate slotDate, LocalTime slotStart, String status, long bookedBy) {
        long id = jdbc.queryForObject("""
                INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                        slot_start, status, channel, booked_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'COUNTER', ?) RETURNING id""", Long.class,
                "LH-IT-" + UUID.randomUUID().toString().substring(0, 8), customerId, petId, branchId, serviceId,
                serviceGroup, slotDate, slotStart, status, bookedBy);
        return track(APPOINTMENT, id);
    }

    /**
     * Chèn một Visit {@code WAITING} trỏ tới lịch hẹn — thứ mà bên gọi (T26) ghi cùng transaction với
     * {@code AppointmentApi.checkIn}. Dùng {@link JdbcTemplate} nên tham gia transaction đang chạy của test. Xóa ở
     * {@link #cleanUp()} (trước {@code appointments}, FK {@code RESTRICT}).
     */
    public long visit(long appointmentId, long checkedInBy) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, appointment_id, service_group, status,
                        priority_class, queue_sort_key, checked_in_by, checked_in_at)
                SELECT ?, branch_id, customer_id, pet_id, id, service_group, 'WAITING', 'APPOINTMENT', now(), ?, now()
                FROM appointments WHERE id = ? RETURNING id""", Long.class,
                "VS-IT-" + UUID.randomUUID().toString().substring(0, 8), checkedInBy, appointmentId);
    }

    /** Nhân viên A05–A08 có hồ sơ (chi nhánh {@code branchId}) hoặc ADMIN / SUPER_MANAGER ({@code branchId} null). */
    public long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'x', ?, 'ACTIVE') RETURNING id""",
                Long.class, "appt-" + UUID.randomUUID() + "@example.com", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + UUID.randomUUID(), branchId);
        return track(ACCOUNT, id);
    }

    /** Hồ sơ online gắn tài khoản khách (BR-KH-01: SĐT không bắt buộc). */
    public long onlineCustomer(long accountId, String fullName) {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE') RETURNING id""",
                Long.class, accountId, fullName);
        return track(CUSTOMER, id);
    }

    /** Một lần hạn chế đặt online (BR-LH-09); {@code liftedAt} null = chưa gỡ. Xóa cùng hồ sơ khách. */
    public void restriction(long customerId, Instant startsAt, Instant endsAt, Instant liftedAt, Long liftedBy) {
        jdbc.update("""
                INSERT INTO booking_restrictions (customer_id, starts_at, ends_at, violation_count, lifted_at, lifted_by)
                VALUES (?, ?, ?, 3, ?, ?)""", customerId, Timestamp.from(startsAt), Timestamp.from(endsAt),
                liftedAt == null ? null : Timestamp.from(liftedAt), liftedBy);
    }

    /** Tài khoản khách, không có hồ sơ khách (xem khung trống không đọc {@code customers}). */
    public long customerAccount() {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'x', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, "appt-" + UUID.randomUUID() + "@example.com");
        return track(ACCOUNT, id);
    }

    /** Cùng các khoảng giờ cho cả 7 ngày, hiệu lực từ {@code effectiveFrom}; mỗi khoảng là cặp {@code open, close}. */
    public void openingHours(long branchId, LocalDate effectiveFrom, LocalTime open1, LocalTime close1,
            LocalTime open2, LocalTime close2) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1, open_2, close_2)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", branchId, effectiveFrom, day, open1, close1, open2, close2);
        }
    }

    public void holiday(long branchId, LocalDate date, long createdBy) {
        jdbc.update("INSERT INTO holidays (branch_id, holiday_date, reason, created_by) VALUES (?, ?, 'IT', ?)",
                branchId, date, createdBy);
    }

    public void branchService(long branchId, long serviceId, boolean enabled) {
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, ?)", branchId,
                serviceId, enabled);
    }

    public void quotaDefault(long branchId, String serviceGroup, int quota, long updatedBy) {
        jdbc.update("""
                INSERT INTO branch_quota_defaults (branch_id, service_group, default_quota, updated_by)
                VALUES (?, ?, ?, ?)""", branchId, serviceGroup, quota, updatedBy);
    }

    public void slotQuota(long branchId, String serviceGroup, LocalDate date, LocalTime start, int quota,
            long updatedBy) {
        jdbc.update("""
                INSERT INTO slot_quotas (branch_id, service_group, slot_date, slot_start, quota, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)""", branchId, serviceGroup, date, start, quota, updatedBy);
    }

    /**
     * Xóa con trước cha. Trước {@code services}: dữ liệu con của các chi nhánh đã ghi nhận ({@code branch_services},
     * giờ mở cửa, ngày nghỉ, quota) và {@code sessions} / {@code staff_profiles} của các tài khoản đã ghi nhận (hồ sơ trỏ
     * tới chi nhánh) — IT không tạo những dòng này thì các câu đó xóa 0 dòng.
     */
    public void cleanUp() {
        for (long[] entry : created) {
            if (entry[0] == PET) {
                jdbc.update("DELETE FROM visits WHERE pet_id = ?", entry[1]);
            } else if (entry[0] == APPOINTMENT) {
                jdbc.update("DELETE FROM visits WHERE appointment_id = ?", entry[1]);
            }
        }
        for (long[] entry : created) {
            if (entry[0] == PET) {
                jdbc.update("DELETE FROM appointments WHERE pet_id = ?", entry[1]);
            }
        }
        for (int table = 0; table < TABLES.length; table++) {
            if (table == SERVICE) {
                deleteBranchAndAccountChildren();
            }
            if (table == CUSTOMER) {
                for (long[] entry : created) {
                    if (entry[0] == CUSTOMER) {
                        jdbc.update("DELETE FROM booking_restrictions WHERE customer_id = ?", entry[1]);
                    }
                }
            }
            for (long[] entry : created) {
                if (entry[0] == table) {
                    jdbc.update("DELETE FROM " + TABLES[table] + " WHERE id = ?", entry[1]);
                }
            }
        }
        created.clear();
    }

    private void deleteBranchAndAccountChildren() {
        for (long[] entry : created) {
            if (entry[0] == BRANCH) {
                for (String child : BRANCH_CHILDREN) {
                    jdbc.update("DELETE FROM " + child + " WHERE branch_id = ?", entry[1]);
                }
            } else if (entry[0] == ACCOUNT) {
                jdbc.update("DELETE FROM sessions WHERE account_id = ?", entry[1]);
                jdbc.update("DELETE FROM staff_profiles WHERE account_id = ?", entry[1]);
            }
        }
    }

    private long track(int table, long id) {
        created.push(new long[] {table, id});
        return id;
    }
}
