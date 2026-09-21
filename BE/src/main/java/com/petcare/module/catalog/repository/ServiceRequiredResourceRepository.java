package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.ServiceRequiredResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRequiredResourceRepository extends JpaRepository<ServiceRequiredResource, UUID> {

    List<ServiceRequiredResource> findAllByServiceId(UUID serviceId);

    void deleteAllByServiceId(UUID serviceId);
}
