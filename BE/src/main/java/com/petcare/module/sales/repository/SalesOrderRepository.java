package com.petcare.module.sales.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.sales.entity.SalesOrder;

import jakarta.persistence.LockModeType;

/**
 * Order (docs/adr/0044). Mọi thao tác ghi khóa dòng Order {@code FOR NO KEY UPDATE} <b>trước</b> khi đọc entity; không
 * {@code findById} rồi mới khóa (persistence context cũ, ADR-0029 §4).
 */
public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.id = :id")
    Optional<SalesOrder> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SalesOrder o WHERE o.visitId = :visitId")
    Optional<SalesOrder> findByVisitIdForUpdate(@Param("visitId") Long visitId);

    @Query("SELECT o FROM SalesOrder o WHERE o.visitId = :visitId")
    Optional<SalesOrder> findByVisitId(@Param("visitId") Long visitId);
}
