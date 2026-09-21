package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.StoreProductOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreProductOverrideRepository extends JpaRepository<StoreProductOverride, UUID> {

    Optional<StoreProductOverride> findByStoreIdAndProductId(UUID storeId, UUID productId);

    List<StoreProductOverride> findAllByStoreId(UUID storeId);

    boolean existsByStoreIdAndProductId(UUID storeId, UUID productId);
}
