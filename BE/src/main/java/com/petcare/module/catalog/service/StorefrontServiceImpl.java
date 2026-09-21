package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.EffectiveProductResponse;
import com.petcare.module.catalog.dto.EffectiveServiceResponse;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.StoreProductOverride;
import com.petcare.module.catalog.entity.StoreServiceOverride;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.StoreProductOverrideRepository;
import com.petcare.module.catalog.repository.StoreServiceOverrideRepository;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Module 05 — effective/storefront view. Cần biết Store thuộc Organization nào để lấy đúng danh
 * mục gốc; đi qua {@code organization.service.StoreService#getOrganizationIdForStore} (service
 * interface của module khác, không phải repository/entity — đúng "Backend architecture" ở
 * CLAUDE.md) thay vì {@code StoreService#getStore} thông thường vì hàm đó áp
 * {@code assertCanManageStore}, không nhận role CUSTOMER (RULE-05-06 cho Customer xem storefront).
 */
@Service
@RequiredArgsConstructor
public class StorefrontServiceImpl implements StorefrontService {

    private final com.petcare.module.organization.service.StoreService storeService;
    private final ProductRepository productRepository;
    private final ServiceRepository serviceRepository;
    private final StoreProductOverrideRepository storeProductOverrideRepository;
    private final StoreServiceOverrideRepository storeServiceOverrideRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EffectiveProductResponse> listStoreProducts(UUID storeId, UserPrincipal actor, Boolean activeOnly, Pageable pageable) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        assertViewable(actor, organizationId, storeId);
        boolean effectiveActiveOnly = actor.getRole() == UserRole.CUSTOMER || Boolean.TRUE.equals(activeOnly);

        Map<UUID, StoreProductOverride> overrideByProductId = storeProductOverrideRepository.findAllByStoreId(storeId).stream()
                .collect(java.util.stream.Collectors.toMap(StoreProductOverride::getProductId, Function.identity()));

        List<EffectiveProductResponse> items = productRepository.findAllByOrganizationId(organizationId).stream()
                .filter(product -> !effectiveActiveOnly || product.isActive())
                .map(product -> toEffective(product, overrideByProductId.get(product.getId())))
                .toList();

        return PageResponse.of(paginate(items, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EffectiveServiceResponse> listStoreServices(UUID storeId, UserPrincipal actor, Boolean activeOnly, Pageable pageable) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        assertViewable(actor, organizationId, storeId);
        boolean effectiveActiveOnly = actor.getRole() == UserRole.CUSTOMER || Boolean.TRUE.equals(activeOnly);

        Map<UUID, StoreServiceOverride> overrideByServiceId = storeServiceOverrideRepository.findAllByStoreId(storeId).stream()
                .collect(java.util.stream.Collectors.toMap(StoreServiceOverride::getServiceId, Function.identity()));

        List<EffectiveServiceResponse> items = serviceRepository.findAllByOrganizationId(organizationId).stream()
                .map(service -> toEffective(service, overrideByServiceId.get(service.getId())))
                .filter(item -> !effectiveActiveOnly || item.isActive())
                .toList();

        return PageResponse.of(paginate(items, pageable));
    }

    private void assertViewable(UserPrincipal actor, UUID organizationId, UUID storeId) {
        if (actor.getRole() == UserRole.CUSTOMER) {
            // RULE-05-06 — Customer không gắn Organization/Store (RULE-02-02), được xem storefront
            // của mọi Store; kết quả tự lọc activeOnly=true, không phải authorization ở đây.
            return;
        }
        // Staff (SUPER_ADMIN/ORGANIZATION_ADMIN/STORE_MANAGER) — tái dùng assertCanManageStore cho
        // đọc, cùng lý do StoreServiceImpl.getStore đã áp dụng (assertCanManageStore không chỉ dành
        // riêng cho ghi).
        RoleScopeGuard.assertCanManageStore(actor, organizationId, storeId);
    }

    private static EffectiveProductResponse toEffective(Product product, StoreProductOverride override) {
        BigDecimal price = override != null ? override.getPrice() : product.getBasePrice();
        // store_products không có cột khả dụng riêng (bất đối xứng ERD) — isActive luôn phản ánh
        // Product gốc, không có khái niệm override khả dụng cho Product.
        return new EffectiveProductResponse(product.getId(), product.getName(), formatMoney(price), product.isActive());
    }

    private static EffectiveServiceResponse toEffective(com.petcare.module.catalog.entity.Service service, StoreServiceOverride override) {
        BigDecimal price = override != null ? override.getPrice() : service.getBasePrice();
        // ASSUMPTION — chưa có override (Store chưa initializeOverridesForStore, RULE-05-07) =>
        // coi là chưa mở bán tại Store đó (false), không fallback về services.is_active gốc.
        boolean isActive = override != null && override.isActive();
        return new EffectiveServiceResponse(service.getId(), service.getName(), formatMoney(price), isActive, service.getDurationMinutes());
    }

    private static <T> Page<T> paginate(List<T> items, Pageable pageable) {
        int start = (int) pageable.getOffset();
        if (start >= items.size()) {
            return new PageImpl<>(List.of(), pageable, items.size());
        }
        int end = Math.min(start + pageable.getPageSize(), items.size());
        return new PageImpl<>(items.subList(start, end), pageable, items.size());
    }

    private static String formatMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
