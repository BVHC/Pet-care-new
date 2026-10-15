package com.petcare.module.appointment.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.appointment.entity.BookingRestriction;

public interface BookingRestrictionRepository extends JpaRepository<BookingRestriction, Long> {

    /**
     * Khách đang bị hạn chế đặt online tại thời điểm {@code at} (BR-LH-09, erd §5): {@code starts_at ≤ at < ends_at} và
     * chưa gỡ sớm. Dùng index {@code ix_booking_restrictions_customer_ends_at} (V1). Không khóa (docs/adr/0043).
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM booking_restrictions
                WHERE customer_id = :customerId AND ends_at > :at AND starts_at <= :at AND lifted_at IS NULL)
            """, nativeQuery = true)
    boolean existsActive(@Param("customerId") Long customerId, @Param("at") Instant at);
}
