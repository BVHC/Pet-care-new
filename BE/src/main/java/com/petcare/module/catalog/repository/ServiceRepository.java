package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    Page<Service> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    List<Service> findAllByOrganizationId(UUID organizationId);
}
