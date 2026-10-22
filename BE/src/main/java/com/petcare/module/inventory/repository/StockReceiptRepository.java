package com.petcare.module.inventory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.inventory.entity.StockReceipt;

import jakarta.persistence.LockModeType;

/** Phiếu nhập (docs/adr/0042). Khóa ghi dòng phiếu {@code FOR NO KEY UPDATE} trước mọi lần đọc entity. */
public interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StockReceipt r WHERE r.id = :id")
    Optional<StockReceipt> findByIdForUpdate(@Param("id") Long id);
}
