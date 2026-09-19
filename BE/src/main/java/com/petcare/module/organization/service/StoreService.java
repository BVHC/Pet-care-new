package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface StoreService {

    StoreResponse createStore(UUID organizationId, CreateStoreRequest request, UserPrincipal actor);

    StoreResponse updateStore(UUID storeId, UpdateStoreRequest request, UserPrincipal actor);

    StoreResponse getStore(UUID storeId, UserPrincipal actor);

    PageResponse<StoreResponse> listStores(UUID organizationId, UserPrincipal actor, Pageable pageable);
}
