package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.OrganizationPolicyResponse;
import com.petcare.module.organization.dto.UpdateOrganizationPolicyRequest;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

public interface OrganizationPolicyService {

    OrganizationPolicyResponse getPolicy(UUID organizationId, UserPrincipal actor);

    OrganizationPolicyResponse updatePolicy(UUID organizationId, UpdateOrganizationPolicyRequest request,
                                             UserPrincipal actor);
}
