package com.petcare.module.boarding.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;

import jakarta.persistence.LockModeType;

/**
 * Đặt chỗ lưu trú (docs/adr/0065). Mọi thao tác ghi khóa dòng {@code FOR NO KEY UPDATE} trước khi đọc entity; không
 * {@code findById} rồi mới khóa (persistence context cũ, ADR-0029 §4).
 */
public interface BoardingBookingRepository extends JpaRepository<BoardingBooking, Long> {

    interface BookingKeys {
        Long getBranchId();

        Long getCustomerId();

        Long getPetId();
    }

    /** Khóa ngoại bất biến của đặt chỗ, đọc không khóa để phân quyền và lấy khóa thú / chi nhánh trước. */
    @Query(value = "SELECT branch_id AS branchId, customer_id AS customerId, pet_id AS petId "
            + "FROM boarding_bookings WHERE id = :id", nativeQuery = true)
    Optional<BookingKeys> findKeys(@Param("id") Long id);

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BoardingBooking b WHERE b.id = :id")
    Optional<BoardingBooking> findByIdForUpdate(@Param("id") Long id);

    /** Khóa theo lô trong một câu, id tăng dần (hủy do phòng khám). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BoardingBooking b WHERE b.id IN :ids ORDER BY b.id")
    List<BoardingBooking> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * Đặt chỗ còn hiệu lực của thú có khoảng ngày chồng {@code [from, to)}; {@code excludeId = 0} khi đặt mới
     * (BR-LT-02).
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM boarding_bookings
                           WHERE pet_id = :petId AND id <> :excludeId
                             AND status IN ('BOOKED', 'CHECKED_IN', 'OVERDUE')
                             AND daterange(check_in_date, check_out_date) && daterange(:from, :to))
            """, nativeQuery = true)
    boolean existsActiveOverlap(@Param("petId") Long petId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("excludeId") long excludeId);

    interface NightOccupancy {
        LocalDate getNight();

        int getOccupied();
    }

    /**
     * Số đặt chỗ đang chiếm chỗ mỗi đêm của {@code [from, to)} (BR-LT-03): {@code BOOKED} / {@code CHECKED_IN} các đêm
     * từ ngày nhận tới trước ngày trả, {@code OVERDUE} từ {@code today} tới {@code overdueUntil}. Dùng
     * {@code ix_boarding_bookings_capacity}.
     */
    @Query(value = """
            SELECT CAST(d AS date) AS night, CAST(count(b.id) AS integer) AS occupied
            FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') d
            LEFT JOIN boarding_bookings b
              ON b.branch_id = :branchId AND b.kennel_type_id = :kennelTypeId
             AND ((b.status IN ('BOOKED', 'CHECKED_IN')
                   AND b.check_in_date <= CAST(d AS date) AND CAST(d AS date) < b.check_out_date)
               OR (b.status = 'OVERDUE'
                   AND CAST(d AS date) BETWEEN CAST(:today AS date) AND CAST(:overdueUntil AS date)))
            GROUP BY d ORDER BY d
            """, nativeQuery = true)
    List<NightOccupancy> countOccupiedByNight(@Param("branchId") Long branchId,
            @Param("kennelTypeId") Long kennelTypeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("today") LocalDate today, @Param("overdueUntil") LocalDate overdueUntil);

    /** {@code updated_at} đã lưu: thời điểm vi phạm truyền cho {@code evaluateAfterViolation} (D3). */
    @Query(value = "SELECT updated_at FROM boarding_bookings WHERE id = :id", nativeQuery = true)
    Instant findUpdatedAt(@Param("id") Long id);

    interface KennelBooking {
        Long getKennelId();

        Long getBookingId();
    }

    /** Đặt chỗ đang ở trong chuồng (CHECKED_IN / OVERDUE) của các chuồng đã cho. */
    @Query(value = """
            SELECT kennel_id AS kennelId, id AS bookingId FROM boarding_bookings
            WHERE kennel_id IN (:kennelIds) AND status IN ('CHECKED_IN', 'OVERDUE')
            """, nativeQuery = true)
    List<KennelBooking> findCurrentByKennelIds(@Param("kennelIds") Collection<Long> kennelIds);

    /** Đặt chỗ {@code status} của một loại chuồng tại chi nhánh còn ngày trả sau {@code date}, theo ngày nhận rồi id. */
    List<BoardingBooking> findByBranchIdAndKennelTypeIdAndStatusAndCheckOutDateAfterOrderByCheckInDateAscIdAsc(
            Long branchId, Long kennelTypeId, BoardingBookingStatus status, LocalDate date);

    /** Id đặt chỗ (trong {@code bookingIds}) đã có nhật ký chăm sóc ngày {@code date} (BR-LT-11). */
    @Query(value = """
            SELECT DISTINCT boarding_booking_id FROM care_logs
            WHERE boarding_booking_id IN (:bookingIds) AND log_date = :date
            """, nativeQuery = true)
    List<Long> findBookingIdsWithCareLogOn(@Param("bookingIds") Collection<Long> bookingIds,
            @Param("date") LocalDate date);

    interface BookedDue {
        Long getId();

        LocalDate getCheckInDate();
    }

    /**
     * ST05 lô đầu: tối đa {@code limit} đặt chỗ {@code BOOKED} có ngày nhận ≤ {@code today}, theo
     * {@code (check_in_date, id)} — đúng thứ tự của {@code ix_boarding_bookings_booked_check_in} (V20). Không khóa;
     * {@link #lockBookedForNoShow} kiểm lại dưới khóa. Trạng thái để literal để khớp index partial.
     */
    @Query(value = """
            SELECT id AS id, check_in_date AS checkInDate FROM boarding_bookings
            WHERE status = 'BOOKED' AND check_in_date <= :today
            ORDER BY check_in_date, id LIMIT :limit
            """, nativeQuery = true)
    List<BookedDue> findBookedDue(@Param("today") LocalDate today, @Param("limit") int limit);

    /** ST05 các lô sau: tiếp từ {@code (afterDate, afterId)} loại trừ. */
    @Query(value = """
            SELECT id AS id, check_in_date AS checkInDate FROM boarding_bookings
            WHERE status = 'BOOKED' AND check_in_date <= :today
              AND (check_in_date, id) > (CAST(:afterDate AS date), :afterId)
            ORDER BY check_in_date, id LIMIT :limit
            """, nativeQuery = true)
    List<BookedDue> findBookedDueAfter(@Param("today") LocalDate today, @Param("afterDate") LocalDate afterDate,
            @Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * Khóa một đặt chỗ BOOKED bỏ qua dòng đang bị luồng khác giữ ({@code SKIP LOCKED}): khách đang hủy hoặc đang gia hạn
     * thì lượt sau xét lại. Rỗng nếu đã rời BOOKED hoặc đang bị khóa. {@code MANDATORY}: ngoài transaction thì khóa nhả
     * ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id FROM boarding_bookings WHERE id = :id AND status = 'BOOKED'
            FOR NO KEY UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockBookedForNoShow(@Param("id") long id);

    /** Id mọi đặt chỗ BOOKED của thú (không khóa; {@link #findAllByIdInForUpdate} khóa và kiểm lại trạng thái). */
    @Query(value = "SELECT id FROM boarding_bookings WHERE pet_id = :petId AND status = 'BOOKED' ORDER BY id",
            nativeQuery = true)
    List<Long> findBookedIdsByPetId(@Param("petId") Long petId);
}
