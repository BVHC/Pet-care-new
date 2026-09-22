package com.petcare.module.organization.repository;

import com.petcare.module.organization.entity.StoreResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoreResourceRepository extends JpaRepository<StoreResource, UUID> {

    boolean existsByStoreIdAndResourceCode(UUID storeId, String resourceCode);

    List<StoreResource> findAllByStoreId(UUID storeId);

    // RULE-03-02 (ActivateStore, điều kiện 2) — ít nhất 1 tài nguyên đang active.
    boolean existsByStoreIdAndActiveTrue(UUID storeId);
}
