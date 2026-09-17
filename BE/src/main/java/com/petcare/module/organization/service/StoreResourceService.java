package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreResourceRequest;
import com.petcare.module.organization.dto.StoreResourceListResponse;
import com.petcare.module.organization.dto.StoreResourceResponse;
import com.petcare.module.organization.dto.UpdateStoreResourceRequest;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

public interface StoreResourceService {

    StoreResourceResponse createResource(UUID storeId, CreateStoreResourceRequest request, UserPrincipal actor);

    StoreResourceResponse updateResource(UUID storeId, UUID resourceId, UpdateStoreResourceRequest request,
                                          UserPrincipal actor);

    StoreResourceListResponse listResources(UUID storeId, UserPrincipal actor);
}
