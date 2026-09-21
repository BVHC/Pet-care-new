package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/catalog-v1.md C1 — ManageService/ViewService (RULE-05-03, RULE-05-06). Tên
 * "ServiceCatalogService" (không phải "ServiceService") để tránh trùng tên đôi khó đọc với
 * entity {@link com.petcare.module.catalog.entity.Service}.
 */
public interface ServiceCatalogService {

    ServiceResponse createService(CreateServiceRequest request, UserPrincipal actor);

    ServiceResponse updateService(UUID serviceId, UpdateServiceRequest request, UserPrincipal actor);

    ServiceResponse getService(UUID serviceId, UserPrincipal actor);

    PageResponse<ServiceResponse> listServices(UserPrincipal actor, Pageable pageable);
}
