package com.petcare.module.inventory.repository;

import com.petcare.module.inventory.entity.InventoryBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, UUID> {

    Optional<InventoryBatch> findByStoreIdAndProductIdAndBatchNumber(UUID storeId, UUID productId, String batchNumber);

    /**
     * RULE-12-11 (FEFO) — ứng viên xuất kho: bỏ qua lô đã hết hạn ({@code expiryDate < today}),
     * ưu tiên hết hạn sớm nhất trước. Lô không có hạn dùng ({@code expiryDate IS NULL}) xếp sau
     * cùng — mặc định Postgres cho {@code ORDER BY ... ASC} là NULLS LAST, đúng ý nghĩa "không
     * hết hạn thì tiêu thụ sau cùng" mà không cần NULLS LAST tường minh.
     */
    @Query("""
            SELECT b FROM InventoryBatch b
            WHERE b.storeId = :storeId AND b.productId = :productId AND b.quantity > 0
              AND (b.expiryDate IS NULL OR b.expiryDate >= :today)
            ORDER BY b.expiryDate ASC, b.manufactureDate ASC, b.createdAt ASC
            """)
    List<InventoryBatch> findFefoCandidates(@Param("storeId") UUID storeId, @Param("productId") UUID productId,
                                             @Param("today") LocalDate today);

    /** TrackBatch/TrackExpiry (read) — filter tùy chọn theo productId/expiredOnly/expiringBefore. */
    @Query("""
            SELECT b FROM InventoryBatch b
            WHERE b.storeId = :storeId
              AND (:productId IS NULL OR b.productId = :productId)
              AND (:expiredOnly = FALSE OR (b.expiryDate IS NOT NULL AND b.expiryDate < :today))
              AND (:expiringBefore IS NULL OR (b.expiryDate IS NOT NULL AND b.expiryDate <= :expiringBefore))
            ORDER BY b.expiryDate ASC, b.manufactureDate ASC, b.createdAt ASC
            """)
    Page<InventoryBatch> search(@Param("storeId") UUID storeId, @Param("productId") UUID productId,
                                 @Param("expiredOnly") boolean expiredOnly,
                                 @Param("expiringBefore") LocalDate expiringBefore,
                                 @Param("today") LocalDate today, Pageable pageable);
}
