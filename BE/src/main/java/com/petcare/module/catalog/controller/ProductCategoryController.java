package com.petcare.module.catalog.controller;

import java.util.List;

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

import com.petcare.module.catalog.dto.ProductCategoryRequest;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.UpdateProductCategoryRequest;
import com.petcare.module.catalog.service.ProductCategoryService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** {@code /api/product-categories} của catalog-v1 (UC28). */
@RestController
@RequestMapping("/api/product-categories")
public class ProductCategoryController {

    private final ProductCategoryService categories;

    public ProductCategoryController(ProductCategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<List<ProductCategoryResponse>> list(@RequestParam(required = false) Boolean isActive) {
        return ApiResponse.ok(categories.listCategories(isActive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ProductCategoryResponse> create(@Valid @RequestBody ProductCategoryRequest request) {
        return ApiResponse.created(categories.createCategory(request), "Đã tạo danh mục sản phẩm");
    }

    @PatchMapping("/{categoryId}")
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<ProductCategoryResponse> update(@PathVariable Long categoryId,
            @Valid @RequestBody UpdateProductCategoryRequest request) {
        return ApiResponse.ok(categories.updateCategory(categoryId, request), "Đã cập nhật danh mục sản phẩm");
    }
}
