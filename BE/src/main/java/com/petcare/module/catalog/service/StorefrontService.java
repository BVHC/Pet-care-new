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

    /**
     * Cross-module (Module 14 Order, RULE-14-01) — giá hiệu lực 1 Product tại Store (override nếu
     * có, else {@code products.base_price}), dùng snapshot {@code unit_price} lúc CreateOrder.
     * Không qua actor guard — caller (Order) đã tự authorize actor ở tầng của mình trước khi gọi,
     * cùng convention {@code ProductService#getProductForCrossModule}.
     */
    String getEffectiveProductPrice(UUID storeId, UUID productId);
}
