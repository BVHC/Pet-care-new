package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.StorePolicyResponse;
import com.petcare.module.organization.dto.UpdateStorePolicyRequest;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

public interface StorePolicyService {

    StorePolicyResponse getPolicy(UUID storeId, UserPrincipal actor);

    StorePolicyResponse updatePolicy(UUID storeId, UpdateStorePolicyRequest request, UserPrincipal actor);
}
