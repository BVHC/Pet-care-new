package com.petcare.module.organization.repository;

import com.petcare.module.organization.entity.StorePolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StorePolicyRepository extends JpaRepository<StorePolicy, UUID> {

    Optional<StorePolicy> findByStoreId(UUID storeId);
}
