package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.EffectiveProductResponse;
import com.petcare.module.catalog.dto.EffectiveServiceResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/catalog-v1.md C3 — ViewProduct/ViewService storefront (RULE-05-06). {@code category}/
 * {@code q} filters ở contract là PROPOSED, không CONFIRMED — không implement trong v1
 * (docs/api/00-method.md YAGNI: "pagination/filter... chưa có trong docs → PROPOSED + TBD, không
 * mặc định CONFIRMED"), chỉ {@code activeOnly} vì nó mã hóa trực tiếp RULE-05-06.
 */
public interface StorefrontService {

    PageResponse<EffectiveProductResponse> listStoreProducts(UUID storeId, UserPrincipal actor, Boolean activeOnly, Pageable pageable);

    PageResponse<EffectiveServiceResponse> listStoreServices(UUID storeId, UserPrincipal actor, Boolean activeOnly, Pageable pageable);
}
