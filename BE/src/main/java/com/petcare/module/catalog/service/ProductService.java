package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** docs/api/catalog-v1.md C1 — ManageProduct/ViewProduct (RULE-05-01, RULE-05-02, RULE-05-06). */
public interface ProductService {

    ProductResponse createProduct(CreateProductRequest request, UserPrincipal actor);

    ProductResponse updateProduct(UUID productId, UpdateProductRequest request, UserPrincipal actor);

    ProductResponse getProduct(UUID productId, UserPrincipal actor);

    PageResponse<ProductResponse> listProducts(UserPrincipal actor, Pageable pageable);

    /**
     * Đọc Product không qua actor-guard — dùng cho module khác cần dữ liệu Product (vd Inventory
     * xác minh Product cùng Organization với Store trước khi Receive/Issue, RULE-12-01), cùng
     * kiểu {@code StoreService#getOrganizationIdForStore} (đã có tiền lệ cross-module ở
     * {@code StorefrontServiceImpl}). Không dùng cho Controller — không kiểm tra quyền xem.
     */
    ProductResponse getProductForCrossModule(UUID productId);
}
