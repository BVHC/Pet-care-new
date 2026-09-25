package com.petcare.module.procurement.service;

import com.petcare.module.procurement.dto.CreateSupplierRequest;
import com.petcare.module.procurement.dto.SupplierResponse;
import com.petcare.module.procurement.dto.UpdateSupplierRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** docs/api/procurement-v1.md — ManageSupplier (RULE-13-04, Organization Admin). */
public interface SupplierService {

    SupplierResponse createSupplier(CreateSupplierRequest request, UserPrincipal actor);

    SupplierResponse updateSupplier(UUID supplierId, UpdateSupplierRequest request, UserPrincipal actor);

    SupplierResponse getSupplier(UUID supplierId, UserPrincipal actor);

    PageResponse<SupplierResponse> listSuppliers(UserPrincipal actor, Pageable pageable);
}
