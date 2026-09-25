package com.petcare.module.procurement.repository;

import com.petcare.module.procurement.entity.Supplier;
import com.petcare.platform.enums.SupplierStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    Page<Supplier> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    Page<Supplier> findAllByOrganizationIdAndStatus(UUID organizationId, SupplierStatus status, Pageable pageable);
}
