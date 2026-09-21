package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.StoreServiceOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreServiceOverrideRepository extends JpaRepository<StoreServiceOverride, UUID> {

    Optional<StoreServiceOverride> findByStoreIdAndServiceId(UUID storeId, UUID serviceId);

    List<StoreServiceOverride> findAllByStoreId(UUID storeId);

    boolean existsByStoreIdAndServiceId(UUID storeId, UUID serviceId);
}
