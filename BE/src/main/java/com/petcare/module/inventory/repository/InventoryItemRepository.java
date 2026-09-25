package com.petcare.module.inventory.repository;

import com.petcare.module.inventory.entity.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Optional<InventoryItem> findByStoreIdAndProductId(UUID storeId, UUID productId);

    @Query("""
            SELECT i FROM InventoryItem i
            WHERE i.storeId = :storeId
              AND (:productId IS NULL OR i.productId = :productId)
              AND (:lowOnly = FALSE OR i.quantityAvailable <= i.minStockLevel)
            """)
    Page<InventoryItem> search(@Param("storeId") UUID storeId, @Param("productId") UUID productId,
                                @Param("lowOnly") boolean lowOnly, Pageable pageable);
}
