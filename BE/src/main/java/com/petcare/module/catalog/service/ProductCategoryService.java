package com.petcare.module.catalog.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.dto.ProductCategoryRequest;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.UpdateProductCategoryRequest;
import com.petcare.module.catalog.entity.ProductCategory;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.ProductCategoryRepository;
import com.petcare.platform.exception.ResourceNotFoundException;

/** UC28 — danh mục sản phẩm. Danh mục chỉ ẩn, không xóa (catalog-v1 A2). */
@Service
public class ProductCategoryService {

    private final ProductCategoryRepository categories;
    private final CatalogMapper mapper;

    public ProductCategoryService(ProductCategoryRepository categories, CatalogMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ProductCategoryResponse> listCategories(Boolean isActive) {
        Specification<ProductCategory> spec = (root, query, cb) ->
                isActive == null ? cb.conjunction() : cb.equal(root.get("active"), isActive);
        return categories.findAll(spec, Sort.by("name")).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public ProductCategoryResponse createCategory(ProductCategoryRequest request) {
        String name = request.name().trim();
        if (categories.existsByName(name)) {
            throw new DuplicateCatalogEntryException("Tên danh mục đã tồn tại");
        }
        return mapper.toResponse(categories.save(new ProductCategory(name)));
    }

    @Transactional
    public ProductCategoryResponse updateCategory(Long categoryId, UpdateProductCategoryRequest request) {
        ProductCategory category = categories.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Danh mục sản phẩm", categoryId));
        if (request.name() != null) {
            String name = request.name().trim();
            if (categories.existsByNameAndIdNot(name, categoryId)) {
                throw new DuplicateCatalogEntryException("Tên danh mục đã tồn tại");
            }
            category.setName(name);
        }
        if (request.isActive() != null) {
            category.setActive(request.isActive());
        }
        return mapper.toResponse(categories.saveAndFlush(category));
    }
}
