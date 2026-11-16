package com.petcare.module.fees.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Ghi cả hai giá trị, {@code updated_by} và {@code updated_at} bằng một UPDATE, nên PUT giống hệt dòng hiện có vẫn
     * ghi dòng (kiểm bẩn của JPA sẽ bỏ qua; UPDATE hàng loạt không qua listener nên {@code updated_at} đặt tường minh).
     * Gọi sau {@link #findForUpdate()} trong cùng transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ShippingFeeConfig c SET c.baseFee = :baseFee, c.freeThreshold = :freeThreshold,
                   c.updatedBy = :updatedBy, c.updatedAt = :now WHERE c.id = 1""")
    int replace(@Param("baseFee") long baseFee, @Param("freeThreshold") long freeThreshold,
            @Param("updatedBy") Long updatedBy, @Param("now") Instant now);
}
