package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.mapper.ProductMapper;
import com.petcare.module.catalog.mapper.ProductMapperImpl;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-05-01, RULE-05-02, RULE-02-05 (cách ly tenant). */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    private final ProductMapper productMapper = new ProductMapperImpl();

    private ProductServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return principal(role, organizationId, null);
    }

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static CreateProductRequest request(String sku) {
        return new CreateProductRequest(sku, "8801234567890", "Thuc an cho meo", ProductCategory.FOOD,
                ProductUnit.BAG, "199000.00", "150000.00", true);
    }

    private static Product product(UUID id, UUID organizationId, String sku) {
        Product product = new Product(organizationId, sku, "8801234567890", "Thuc an cho meo",
                ProductCategory.FOOD, ProductUnit.BAG, new BigDecimal("199000.00"), new BigDecimal("150000.00"), true);
        product.setId(id);
        return product;
    }

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(productRepository, productMapper);
    }

    @Test
    void createProduct_duplicateSkuInOrg_throwsBusinessRuleViolation_RULE_05_02() {
        UUID organizationId = UUID.randomUUID();
        when(productRepository.existsByOrganizationIdAndSku(organizationId, "SKU01")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(request("SKU01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-05-02"));
    }

    @Test
    void createProduct_raceCondition_dataIntegrityViolation_throwsBusinessRuleViolation() {
        UUID organizationId = UUID.randomUUID();
        when(productRepository.existsByOrganizationIdAndSku(organizationId, "SKU01")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenThrow(new DataIntegrityViolationException("uq_products_org_sku"));

        assertThatThrownBy(() -> service.createProduct(request("SKU01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-05-02"));
    }

    @Test
    void createProduct_ownOrg_savesActive() {
        UUID organizationId = UUID.randomUUID();
        when(productRepository.existsByOrganizationIdAndSku(organizationId, "SKU01")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(UUID.randomUUID());
            return product;
        });

        var response = service.createProduct(request("SKU01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.sku()).isEqualTo("SKU01");
        assertThat(response.organizationId()).isEqualTo(organizationId);
        assertThat(response.basePrice()).isEqualTo("199000.00");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void updateProduct_notFound_throwsResourceNotFound() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(productId, new UpdateProductRequest(null, null, null, null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateProduct_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId, "SKU01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateProduct(productId, new UpdateProductRequest(null, null, null, null, null, null, null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateProduct_deactivate_neverHardDeletes_RULE_05_02() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product existing = product(productId, organizationId, "SKU01");
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateProduct(productId, new UpdateProductRequest(null, null, null, null, null, null, false),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.isActive()).isFalse();
        assertThat(response.sku()).isEqualTo("SKU01");
    }

    @Test
    void updateProduct_partialUpdate_keepsUnspecifiedFieldsUnchanged() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product existing = product(productId, organizationId, "SKU01");
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateProduct(productId,
                new UpdateProductRequest(null, null, null, null, "250000.00", null, null),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.basePrice()).isEqualTo("250000.00");
        assertThat(response.name()).isEqualTo("Thuc an cho meo");
        assertThat(response.costPrice()).isEqualTo("150000.00");
    }

    @Test
    void updateProduct_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId, "SKU01")));
        when(productRepository.save(any(Product.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, productId));

        assertThatThrownBy(() -> service.updateProduct(productId, new UpdateProductRequest(null, null, null, null, null, null, false),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void getProduct_storeManager_ownOrg_allowed() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId, "SKU01")));

        var response = service.getProduct(productId, principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID()));

        assertThat(response.productId()).isEqualTo(productId);
    }

    @Test
    void getProduct_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId, organizationId, "SKU01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.getProduct(productId, actor)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listProducts_scoped_returnsOnlyOrgProducts() {
        UUID organizationId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(productRepository.findAllByOrganizationId(organizationId, pageable))
                .thenReturn(new PageImpl<>(List.of(product(UUID.randomUUID(), organizationId, "SKU01"))));

        PageResponse<?> page = service.listProducts(principal(UserRole.ORGANIZATION_ADMIN, organizationId), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
    }
}
