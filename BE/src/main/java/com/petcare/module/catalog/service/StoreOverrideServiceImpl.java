package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.AvailabilityRequest;
import com.petcare.module.catalog.dto.AvailabilityResponse;
import com.petcare.module.catalog.dto.PriceRequest;
import com.petcare.module.catalog.dto.PriceResponse;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.StoreProductOverride;
import com.petcare.module.catalog.entity.StoreServiceOverride;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.StoreProductOverrideRepository;
import com.petcare.module.catalog.repository.StoreServiceOverrideRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Module 05 — override giá/khả dụng riêng theo Store. Không tra cứu module {@code organization}
 * để xác định Store thuộc Organization nào (tránh phụ thuộc chéo module — chỉ được phép đi qua
 * service của module khác, không phải repository/entity, theo CLAUDE.md "Backend architecture");
 * thay vào đó suy ra {@code organizationId} từ chính Product/Service đang cấu hình — override chỉ
 * có ý nghĩa khi Store và Product/Service cùng Organization, nên guard theo Product/Service org là
 * đủ và tự nhiên (không cần round-trip sang module khác). Cùng lý do
 * {@code ServiceCatalogServiceImpl} — import entity {@code Service} nên annotation Spring phải
 * fully-qualify để tránh trùng tên với {@code org.springframework.stereotype.Service}.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class StoreOverrideServiceImpl implements StoreOverrideService {

    private final ProductRepository productRepository;
    private final ServiceRepository serviceRepository;
    private final StoreProductOverrideRepository storeProductOverrideRepository;
    private final StoreServiceOverrideRepository storeServiceOverrideRepository;

    @Override
    @Transactional
    @Auditable(action = "ConfigureProductPrice", resourceType = "StoreProductOverride")
    public PriceResponse configureProductPrice(@AuditResourceId UUID storeId, UUID productId, PriceRequest request, UserPrincipal actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        RoleScopeGuard.assertCanManageStore(actor, product.getOrganizationId(), storeId);

        // RULE-05-07 — override phải đã được hệ thống khởi tạo khi Store ACTIVE trước đó; endpoint
        // này chỉ PUT sửa, không có nhánh tạo mới (docs/api/catalog-v1.md "Đóng băng phạm vi").
        StoreProductOverride override = storeProductOverrideRepository.findByStoreIdAndProductId(storeId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("StoreProductOverride", storeId + ":" + productId));

        override.setPrice(new BigDecimal(request.price()));
        try {
            override = storeProductOverrideRepository.save(override);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("StoreProductOverride", override.getId());
        }

        return new PriceResponse(storeId, formatMoney(override.getPrice()));
    }

    @Override
    @Transactional
    @Auditable(action = "ConfigureServicePrice", resourceType = "StoreServiceOverride")
    public PriceResponse configureServicePrice(@AuditResourceId UUID storeId, UUID serviceId, PriceRequest request, UserPrincipal actor) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
        RoleScopeGuard.assertCanManageStore(actor, service.getOrganizationId(), storeId);

        StoreServiceOverride override = storeServiceOverrideRepository.findByStoreIdAndServiceId(storeId, serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("StoreServiceOverride", storeId + ":" + serviceId));

        override.setPrice(new BigDecimal(request.price()));
        try {
            override = storeServiceOverrideRepository.save(override);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("StoreServiceOverride", override.getId());
        }

        return new PriceResponse(storeId, formatMoney(override.getPrice()));
    }

    @Override
    @Transactional
    @Auditable(action = "ConfigureServiceAvailability", resourceType = "StoreServiceOverride")
    public AvailabilityResponse configureServiceAvailability(@AuditResourceId UUID storeId, UUID serviceId,
                                                               AvailabilityRequest request, UserPrincipal actor) {
        Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
        RoleScopeGuard.assertCanManageStore(actor, service.getOrganizationId(), storeId);

        StoreServiceOverride override = storeServiceOverrideRepository.findByStoreIdAndServiceId(storeId, serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("StoreServiceOverride", storeId + ":" + serviceId));

        // RULE-05-04 — chỉ đổi override, không đụng services.is_active gốc.
        override.setActive(request.isActive());
        try {
            override = storeServiceOverrideRepository.save(override);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("StoreServiceOverride", override.getId());
        }

        return new AvailabilityResponse(storeId, serviceId, override.isActive());
    }

    @Override
    @Transactional
    public void initializeOverridesForStore(UUID organizationId, UUID storeId) {
        for (Product product : productRepository.findAllByOrganizationId(organizationId)) {
            if (!storeProductOverrideRepository.existsByStoreIdAndProductId(storeId, product.getId())) {
                storeProductOverrideRepository.save(
                        new StoreProductOverride(storeId, product.getId(), product.getBasePrice()));
            }
        }
        for (Service service : serviceRepository.findAllByOrganizationId(organizationId)) {
            if (!storeServiceOverrideRepository.existsByStoreIdAndServiceId(storeId, service.getId())) {
                storeServiceOverrideRepository.save(
                        new StoreServiceOverride(storeId, service.getId(), service.getBasePrice(), service.isActive()));
            }
        }
    }

    private static String formatMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
