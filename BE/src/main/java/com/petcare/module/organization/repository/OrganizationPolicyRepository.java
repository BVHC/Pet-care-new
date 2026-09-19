package com.petcare.module.organization.repository;

import com.petcare.module.organization.entity.OrganizationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationPolicyRepository extends JpaRepository<OrganizationPolicy, UUID> {

    Optional<OrganizationPolicy> findByOrganizationId(UUID organizationId);
}
