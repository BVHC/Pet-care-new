package com.petcare.module.catalog.controller;

import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 05 (Service &amp; Product Catalog) — docs/api/catalog-v1.md §A/C1 (ManageProduct/
 * ViewProduct). Không có SUPER_ADMIN ở POST/PATCH: endpoint không nhận organizationId qua path,
 * chỉ ORGANIZATION_ADMIN mới có organizationId gắn sẵn để suy ra (RULE-05-01).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class ProductController {

    private final ProductService productService;

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/api/products")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = productService.createProduct(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/products")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> listProducts(
            @AuthenticationPrincipal UserPrincipal actor, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(productService.listProducts(actor, pageable)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID productId) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getProduct(productId, actor)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PatchMapping("/api/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.updateProduct(productId, request, actor)));
    }
}
