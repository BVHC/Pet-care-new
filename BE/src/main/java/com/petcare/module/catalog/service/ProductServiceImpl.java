package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.mapper.ProductMapper;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Module 05 (Service &amp; Product Catalog) — phần Product. RULE-05-01 (Organization Admin sở
 * hữu toàn quyền — {@code @PreAuthorize} Controller chỉ cho ORGANIZATION_ADMIN gọi POST/PATCH,
 * không có SUPER_ADMIN: endpoint không nhận organizationId qua path, luôn suy ra từ
 * {@code actor.getOrganizationId()} — SUPER_ADMIN không gắn Organization (RULE-02-02) nên không
 * có cách gọi hợp lệ). RULE-05-02 (UNIQUE org+sku; không hard-delete, chỉ isActive=false).
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    @Transactional
    @Auditable(action = "ManageProduct", resourceType = "Product")
    public ProductResponse createProduct(CreateProductRequest request, UserPrincipal actor) {
        UUID organizationId = actor.getOrganizationId();

        if (productRepository.existsByOrganizationIdAndSku(organizationId, request.sku())) {
            throw new BusinessRuleViolationException("RULE-05-02", "SKU đã tồn tại trong Organization");
        }

        Product product = new Product(organizationId, request.sku(), request.barcode(), request.name(),
                request.category(), request.unit(), new BigDecimal(request.basePrice()),
                request.costPrice() == null ? BigDecimal.ZERO : new BigDecimal(request.costPrice()),
                request.isActive());
        try {
            product = productRepository.save(product);
        } catch (DataIntegrityViolationException ex) {
            // Race condition giữa pre-check existsByOrganizationIdAndSku() và save() — UNIQUE
            // uq_products_org_sku ở DB là guard thật, cùng pattern StoreServiceImpl.createStore.
            throw new BusinessRuleViolationException("RULE-05-02", "SKU đã tồn tại trong Organization");
        }

        return productMapper.toResponse(product);
    }

    @Override
    @Transactional
    @Auditable(action = "ManageProduct", resourceType = "Product")
    public ProductResponse updateProduct(@AuditResourceId UUID productId, UpdateProductRequest request, UserPrincipal actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        RoleScopeGuard.assertCanManageOrganization(actor, product.getOrganizationId());

        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.barcode() != null) {
            product.setBarcode(request.barcode());
        }
        if (request.category() != null) {
            product.setCategory(request.category());
        }
        if (request.unit() != null) {
            product.setUnit(request.unit());
        }
        if (request.basePrice() != null) {
            product.setBasePrice(new BigDecimal(request.basePrice()));
        }
        if (request.costPrice() != null) {
            product.setCostPrice(new BigDecimal(request.costPrice()));
        }
        if (request.isActive() != null) {
            // RULE-05-02 — vô hiệu hóa thay cho xóa; không có endpoint DELETE trong v1.
            product.setActive(request.isActive());
        }

        try {
            product = productRepository.save(product);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Product", productId);
        }

        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId, UserPrincipal actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        RoleScopeGuard.assertCanViewOrganizationCatalog(actor, product.getOrganizationId());
        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listProducts(UserPrincipal actor, Pageable pageable) {
        UUID organizationId = actor.getOrganizationId();
        RoleScopeGuard.assertCanViewOrganizationCatalog(actor, organizationId);

        Page<ProductResponse> page = productRepository.findAllByOrganizationId(organizationId, pageable)
                .map(productMapper::toResponse);
        return PageResponse.of(page);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProductForCrossModule(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        return productMapper.toResponse(product);
    }
}
