package com.petcare.module.boarding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.entity.CareLog;

import jakarta.persistence.LockModeType;

/** Nhật ký chăm sóc (docs/adr/0085). Sửa khóa {@code FOR NO KEY UPDATE} trước khi đọc entity. */
public interface CareLogRepository extends JpaRepository<CareLog, Long> {

    interface LogKeys {
        Long getBookingId();

        Long getRecordedBy();
    }

    /** Khóa ngoại bất biến của mục nhật ký, đọc không khóa để phân quyền trước khi khóa. */
    @Query(value = "SELECT boarding_booking_id AS bookingId, recorded_by AS recordedBy FROM care_logs WHERE id = :id",
            nativeQuery = true)
    Optional<LogKeys> findKeys(@Param("id") Long id);

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CareLog l WHERE l.id = :id")
    Optional<CareLog> findByIdForUpdate(@Param("id") Long id);

    List<CareLog> findByBookingIdOrderByLogDateAscIdAsc(Long bookingId);
}
