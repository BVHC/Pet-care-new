package com.petcare.module.fees.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.fees.entity.ShippingFeeConfig;

import jakarta.persistence.LockModeType;

/** Bảng {@code shipping_fee_configs}: một dòng {@code id = 1} (docs/adr/0140). */
public interface ShippingFeeConfigRepository extends JpaRepository<ShippingFeeConfig, Long> {

    /**
     * Khóa dòng duy nhất ({@code FOR NO KEY UPDATE}) và đọc dưới khóa, để audit {@code before} không bao giờ cũ.
     * {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ShippingFeeConfig c WHERE c.id = 1")
    Optional<ShippingFeeConfig> findForUpdate();
}
