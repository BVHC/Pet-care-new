package com.petcare.module.inventory.repository;

import com.petcare.module.inventory.entity.InventoryAdjustment;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, UUID> {

    Page<InventoryAdjustment> findAllByStoreId(UUID storeId, Pageable pageable);

    Page<InventoryAdjustment> findAllByStoreIdAndStatus(UUID storeId, InventoryAdjustmentStatus status, Pageable pageable);
}
