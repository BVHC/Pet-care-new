package com.petcare.module.catalog.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.Valid;

/** {@code /api/products} của catalog-v1 (UC29). */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<PageResponse<ProductResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) Long vaccineTypeId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "false") boolean retailOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(products.listProducts(q, categoryId, productType, vaccineTypeId, isActive, retailOnly,
                page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.created(products.createProduct(request), "Đã tạo sản phẩm");
    }

    @GetMapping("/{productId}")
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<ProductResponse> get(@PathVariable Long productId) {
        return ApiResponse.ok(products.getProduct(productId));
    }

    @PatchMapping("/{productId}")
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ProductResponse> update(@PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(products.updateProduct(productId, request), "Đã cập nhật sản phẩm");
    }
}
