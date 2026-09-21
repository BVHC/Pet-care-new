package com.petcare.module.catalog.service;

import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.StoreProductOverride;
import com.petcare.module.catalog.entity.StoreServiceOverride;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.StoreProductOverrideRepository;
import com.petcare.module.catalog.repository.StoreServiceOverrideRepository;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.ServiceCategory;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-05-05, RULE-05-06, RULE-05-07 (fallback khi chưa init). */
@ExtendWith(MockitoExtension.class)
class StorefrontServiceImplTest {

    @Mock
    private com.petcare.module.organization.service.StoreService storeService;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private StoreProductOverrideRepository storeProductOverrideRepository;
    @Mock
    private StoreServiceOverrideRepository storeServiceOverrideRepository;

    private StorefrontServiceImpl storefrontService;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static Product product(UUID id, UUID organizationId, boolean isActive) {
        Product product = new Product(organizationId, "SKU01", null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, new BigDecimal("100.00"), new BigDecimal("50.00"), isActive);
        product.setId(id);
        return product;
    }

    private static Service serviceEntity(UUID id, UUID organizationId) {
        Service service = new Service(organizationId, "SV01", "Dich vu", ServiceCategory.CLINICAL,
                new BigDecimal("100.00"), 30, true);
        service.setId(id);
        return service;
    }

    @BeforeEach
    void setUp() {
        storefrontService = new StorefrontServiceImpl(storeService, productRepository, serviceRepository,
                storeProductOverrideRepository, storeServiceOverrideRepository);
    }

    @Test
    void listStoreProducts_customer_differentStoreNoScopeCheck_alwaysActiveOnly_RULE_05_06() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID activeProductId = UUID.randomUUID();
        UUID inactiveProductId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productRepository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(product(activeProductId, organizationId, true), product(inactiveProductId, organizationId, false)));
        when(storeProductOverrideRepository.findAllByStoreId(storeId)).thenReturn(List.of());

        var page = storefrontService.listStoreProducts(storeId, principal(UserRole.CUSTOMER, null, null), null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).productId()).isEqualTo(activeProductId);
        // Không override => fallback base_price (RULE-05-05/07).
        assertThat(page.content().get(0).price()).isEqualTo("100.00");
    }

    @Test
    void listStoreProducts_staffOutsideOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> storefrontService.listStoreProducts(storeId, actor, null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listStoreProducts_overrideExists_usesOverridePrice_RULE_05_05() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productRepository.findAllByOrganizationId(organizationId)).thenReturn(List.of(product(productId, organizationId, true)));
        when(storeProductOverrideRepository.findAllByStoreId(storeId))
                .thenReturn(List.of(new StoreProductOverride(storeId, productId, new BigDecimal("80.00"))));

        var page = storefrontService.listStoreProducts(storeId, principal(UserRole.STORE_MANAGER, organizationId, storeId),
                null, PageRequest.of(0, 20));

        assertThat(page.content().get(0).price()).isEqualTo("80.00");
    }

    @Test
    void listStoreServices_noOverride_notFallbackToMasterIsActive_assumption() {
        // ASSUMPTION (không phải RULE trực tiếp) — chưa initializeOverridesForStore thì coi là
        // false, không fallback services.is_active=true gốc.
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(serviceRepository.findAllByOrganizationId(organizationId)).thenReturn(List.of(serviceEntity(serviceId, organizationId)));
        when(storeServiceOverrideRepository.findAllByStoreId(storeId)).thenReturn(List.of());

        var page = storefrontService.listStoreServices(storeId, principal(UserRole.STORE_MANAGER, organizationId, storeId),
                false, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).isActive()).isFalse();
    }

    @Test
    void listStoreServices_customerActiveOnlyForced_hidesUninitializedOverride() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(serviceRepository.findAllByOrganizationId(organizationId)).thenReturn(List.of(serviceEntity(serviceId, organizationId)));
        when(storeServiceOverrideRepository.findAllByStoreId(storeId)).thenReturn(List.of());

        var page = storefrontService.listStoreServices(storeId, principal(UserRole.CUSTOMER, null, null), null, PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }
}
