package com.petcare.module.organization.repository;

import com.petcare.module.organization.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    Page<Store> findAllByOrganizationId(UUID organizationId, Pageable pageable);
}
