package com.petcare.module.inpatient.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.entity.AdmissionOrder;

import jakarta.persistence.LockModeType;

/** Bảng {@code admission_orders} (docs/adr/0149). Hàm khóa là {@code MANDATORY}, xem {@link AdmissionRepository}. */
public interface AdmissionOrderRepository extends JpaRepository<AdmissionOrder, Long> {

    /** {@code FOR NO KEY UPDATE}: thực hiện hoặc hủy một y lệnh. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM AdmissionOrder o WHERE o.id = :id")
    Optional<AdmissionOrder> findByIdForUpdate(@Param("id") Long id);
}
