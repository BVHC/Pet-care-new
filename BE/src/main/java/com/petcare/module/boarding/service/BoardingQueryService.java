package com.petcare.module.boarding.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;

/**
 * Cài thật {@link BoardingQueryApi} (T31, docs/adr/0065; trả nợ D033). Chỉ đọc {@code boarding_bookings} (và
 * {@code boarding_check_ins}) bằng SQL, không khóa, tham gia transaction của bên gọi: {@link #countViolations} chạy dưới
 * khóa theo khách (advisory 1002) của {@code BookingRestrictionApi.evaluateAfterViolation}, READ COMMITTED.
 */
@Service
public class BoardingQueryService implements BoardingQueryApi {

    private static final String STAY_SELECT = """
            SELECT b.id, b.branch_id, b.customer_id, b.pet_id, b.status, c.emergency_phone
            FROM boarding_bookings b LEFT JOIN boarding_check_ins c ON c.boarding_booking_id = b.id
            """;

    private final JdbcTemplate jdbc;

    public BoardingQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StaySummary> findBooking(Long bookingId) {
        return jdbc.query(STAY_SELECT + " WHERE b.id = ?", (rs, n) -> toSummary(rs), bookingId).stream().findFirst();
    }

    @Override
    public boolean isPetInStay(Long petId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE pet_id = ? AND status IN ('CHECKED_IN', 'OVERDUE'))
                """, Boolean.class, petId));
    }

    @Override
    public boolean existsByPet(Long petId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE pet_id = ?)", Boolean.class, petId));
    }

    @Override
    public List<Long> findBookingIdsByPet(Long petId) {
        return jdbc.queryForList("SELECT id FROM boarding_bookings WHERE pet_id = ? ORDER BY id", Long.class, petId);
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarding_bookings WHERE customer_id = ?)", Boolean.class, customerId));
    }

    @Override
    public List<BookedStay> findBookedFrom(Long branchId, LocalDate fromDate) {
        return jdbc.query("""
                SELECT id, code, pet_id, customer_id, check_in_date, check_out_date FROM boarding_bookings
                WHERE branch_id = ? AND status = 'BOOKED' AND check_out_date >= ? ORDER BY id
                """, (rs, n) -> new BookedStay(rs.getLong("id"), rs.getString("code"), rs.getLong("pet_id"),
                rs.getLong("customer_id"), rs.getObject("check_in_date", LocalDate.class),
                rs.getObject("check_out_date", LocalDate.class)), branchId, fromDate);
    }

    /**
     * Hủy muộn hoặc {@code NO_SHOW} có thời điểm vi phạm {@code > since}. Bảng không có cột thời điểm riêng: dùng
     * {@code updated_at} của dòng đã ở trạng thái cuối (ghi một lần, từ {@code Clock}); xem docs/adr/0065 mục 3.
     */
    @Override
    public int countViolations(Long customerId, Instant since) {
        Integer count = jdbc.queryForObject("""
                SELECT CAST(count(*) AS integer) FROM boarding_bookings
                WHERE customer_id = ? AND ((status = 'CANCELLED' AND late_cancel) OR status = 'NO_SHOW')
                  AND updated_at > ?
                """, Integer.class, customerId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public List<StaySummary> findActiveStaysByCustomer(Long customerId) {
        return jdbc.query(STAY_SELECT + " WHERE b.customer_id = ? AND b.status IN ('BOOKED', 'CHECKED_IN', 'OVERDUE')"
                + " ORDER BY b.id", (rs, n) -> toSummary(rs), customerId);
    }

    private static StaySummary toSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StaySummary(rs.getLong("id"), rs.getLong("branch_id"), rs.getLong("customer_id"),
                rs.getLong("pet_id"), StayStatus.valueOf(rs.getString("status")), rs.getString("emergency_phone"));
    }
}
