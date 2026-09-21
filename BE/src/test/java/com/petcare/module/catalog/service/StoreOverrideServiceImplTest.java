package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.AvailabilityRequest;
import com.petcare.module.catalog.dto.PriceRequest;
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
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-05-04, RULE-05-05, RULE-05-07. */
@ExtendWith(MockitoExtension.class)
class StoreOverrideServiceImplTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private StoreProductOverrideRepository storeProductOverrideRepository;
    @Mock
    private StoreServiceOverrideRepository storeServiceOverrideRepository;

    private StoreOverrideServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static Product product(UUID id, UUID organizationId) {
        Product product = new Product(organizationId, "SKU01", null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, new BigDecimal("100.00"), new BigDecimal("50.00"), true);
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
        service = new StoreOverrideServiceImpl(productRepository, serviceRepository,
                storeProductOverrideRepository, storeServiceOverrideRepository);
    }

    @Test
    void configureProductPrice_overrideNotInitialized_throwsResourceNotFound_RULE_05_07() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId)));
        when(storeProductOverrideRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.configureProductPrice(storeId, productId, new PriceRequest("120.00"),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void configureProductPrice_storeManagerDifferentStore_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.configureProductPrice(UUID.randomUUID(), productId, new PriceRequest("120.00"), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void configureProductPrice_ownStore_updatesOverride_RULE_05_05() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId)));
        StoreProductOverride override = new StoreProductOverride(storeId, productId, new BigDecimal("100.00"));
        when(storeProductOverrideRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(override));
        when(storeProductOverrideRepository.save(any(StoreProductOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.configureProductPrice(storeId, productId, new PriceRequest("120.00"),
                principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.price()).isEqualTo("120.00");
        assertThat(response.storeId()).isEqualTo(storeId);
    }

    @Test
    void configureProductPrice_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId)));
        StoreProductOverride override = new StoreProductOverride(storeId, productId, new BigDecimal("100.00"));
        override.setId(UUID.randomUUID());
        when(storeProductOverrideRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(override));
        when(storeProductOverrideRepository.save(any(StoreProductOverride.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(StoreProductOverride.class, override.getId()));

        assertThatThrownBy(() -> service.configureProductPrice(storeId, productId, new PriceRequest("120.00"),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void configureServiceAvailability_onlyTouchesOverride_neverMasterFlag_RULE_05_04() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service masterService = serviceEntity(serviceId, organizationId);
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(masterService));
        StoreServiceOverride override = new StoreServiceOverride(storeId, serviceId, new BigDecimal("100.00"), true);
        when(storeServiceOverrideRepository.findByStoreIdAndServiceId(storeId, serviceId)).thenReturn(Optional.of(override));
        when(storeServiceOverrideRepository.save(any(StoreServiceOverride.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.configureServiceAvailability(storeId, serviceId, new AvailabilityRequest(false),
                principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.isActive()).isFalse();
        assertThat(masterService.isActive()).isTrue(); // gốc Organization không bị đụng
    }

    @Test
    void initializeOverridesForStore_idempotent_onlyCreatesMissingOverrides_RULE_05_07() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID existingProductId = UUID.randomUUID();
        UUID newProductId = UUID.randomUUID();
        when(productRepository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(product(existingProductId, organizationId), product(newProductId, organizationId)));
        when(serviceRepository.findAllByOrganizationId(organizationId)).thenReturn(List.of());
        when(storeProductOverrideRepository.existsByStoreIdAndProductId(storeId, existingProductId)).thenReturn(true);
        when(storeProductOverrideRepository.existsByStoreIdAndProductId(storeId, newProductId)).thenReturn(false);

        service.initializeOverridesForStore(organizationId, storeId);

        org.mockito.Mockito.verify(storeProductOverrideRepository, org.mockito.Mockito.times(1))
                .save(any(StoreProductOverride.class));
    }
}
