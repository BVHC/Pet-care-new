package com.petcare.module.workforce.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.workforce.entity.ShiftRequest;

import jakarta.persistence.LockModeType;

/** Bảng {@code shift_requests} (docs/adr/0138). Hàm khóa là {@code MANDATORY}, xem {@code ShiftDefinitionRepository}. */
public interface ShiftRequestRepository extends JpaRepository<ShiftRequest, Long> {

    /** {@code FOR NO KEY UPDATE}: rút, duyệt, từ chối yêu cầu. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ShiftRequest r WHERE r.id = :id")
    Optional<ShiftRequest> findByIdForUpdate(@Param("id") Long id);
}
