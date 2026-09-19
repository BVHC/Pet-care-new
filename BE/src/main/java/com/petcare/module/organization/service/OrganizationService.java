package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateOrganizationRequest;
import com.petcare.module.organization.dto.OrganizationResponse;
import com.petcare.module.organization.dto.UpdateOrganizationRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrganizationService {

    OrganizationResponse createOrganization(CreateOrganizationRequest request);

    OrganizationResponse updateOrganization(UUID organizationId, UpdateOrganizationRequest request, UserPrincipal actor);

    OrganizationResponse getOrganization(UUID organizationId, UserPrincipal actor);

    PageResponse<OrganizationResponse> listOrganizations(UserPrincipal actor, Pageable pageable);
}
