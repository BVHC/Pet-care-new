package com.petcare.module.appointment.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.appointment.entity.BookingRestriction;

public interface BookingRestrictionRepository extends JpaRepository<BookingRestriction, Long> {

    /**
     * Khách đang bị hạn chế đặt online tại thời điểm {@code at} (BR-LH-09, erd §5): {@code starts_at ≤ at < ends_at} và
     * chưa gỡ sớm. Dùng index {@code ix_booking_restrictions_customer_ends_at} (V1). Không khóa (docs/adr/0047).
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM booking_restrictions
                WHERE customer_id = :customerId AND ends_at > :at AND starts_at <= :at AND lifted_at IS NULL)
            """, nativeQuery = true)
    boolean existsActive(@Param("customerId") Long customerId, @Param("at") Instant at);

    /**
     * Dòng đang hiệu lực tại {@code at}, cùng điều kiện với {@link #existsActive} (erd §5). Chỉ có tối đa một dòng vì hạn
     * chế chỉ được tạo dưới khóa 1002 khi chưa có dòng hiệu lực (docs/adr/0049); hai dòng là dữ liệu hỏng →
     * {@code IncorrectResultSizeDataAccessException} (500), không chọn bừa. Không khóa: luồng gỡ gọi sau khóa 1002
     * (docs/adr/0055).
     */
    @Query("""
            SELECT r FROM BookingRestriction r
            WHERE r.customerId = :customerId AND r.endsAt > :at AND r.startsAt <= :at AND r.liftedAt IS NULL
            """)
    Optional<BookingRestriction> findActive(@Param("customerId") Long customerId, @Param("at") Instant at);

    /**
     * {@code starts_at} của lần hạn chế gần nhất của khách, kể cả lần đã hết hạn hoặc đã gỡ sớm: mốc dưới của phép đếm vi
     * phạm (docs/adr/0049 — mỗi vi phạm chỉ góp vào một lần hạn chế). JPQL để kết quả là {@code Instant} có kiểu (native trả kiểu JDBC tùy driver).
     */
    @Query("SELECT max(r.startsAt) FROM BookingRestriction r WHERE r.customerId = :customerId")
    Optional<Instant> findLatestStartsAt(@Param("customerId") Long customerId);
}
