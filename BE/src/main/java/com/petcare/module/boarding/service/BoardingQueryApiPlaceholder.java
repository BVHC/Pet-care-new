package com.petcare.module.boarding.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;

/**
 * Giữ chỗ cho {@link BoardingQueryApi} tới khi boarding (BE-2) cài thật. Theo 06-module-contracts §1: mọi method
 * ném lỗi, không trả giá trị "an toàn" (trả rỗng sẽ cho thu hẹp giờ mở cửa qua mặt BR-CN-04). Hệ quả: đặt giờ mở
 * cửa, thêm ngày nghỉ và hai endpoint xem trước ảnh hưởng của branch trả 500.
 * BE-2 xóa lớp này (cùng {@code BoardingQueryApiPlaceholderTest}) khi thêm implementation thật.
 * <p>
 * <b>Cài thật một phần (2026-10-16, T17):</b> {@code isPetInStay}, {@code existsByPet}, {@code findBookingIdsByPet},
 * {@code existsByCustomer} đọc thẳng {@code boarding_bookings} trong transaction của bên gọi; <b>2026-10-20 (T29):</b>
 * thêm {@code findBooking} (đọc không khóa, sales cần cho BR-BH-05); các method còn lại vẫn ném.
 */
@Service
public class BoardingQueryApiPlaceholder implements BoardingQueryApi {

    static final String MESSAGE = "BoardingQueryApi chưa được cài (module boarding, BE-2)";

    private final JdbcTemplate jdbc;

    public BoardingQueryApiPlaceholder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StaySummary> findBooking(Long bookingId) {
        return jdbc.query("""
                SELECT b.id, b.branch_id, b.customer_id, b.pet_id, b.status, c.emergency_phone
                FROM boarding_bookings b LEFT JOIN boarding_check_ins c ON c.boarding_booking_id = b.id
                WHERE b.id = ?
                """, (rs, n) -> new StaySummary(rs.getLong("id"), rs.getLong("branch_id"),
                rs.getLong("customer_id"), rs.getLong("pet_id"), StayStatus.valueOf(rs.getString("status")),
                rs.getString("emergency_phone")), bookingId).stream().findFirst();
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
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public int countViolations(Long customerId, Instant since) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<StaySummary> findActiveStaysByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
