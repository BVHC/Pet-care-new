package com.petcare.module.inventory.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.RejectInventoryAdjustmentRequest;
import com.petcare.module.inventory.entity.InventoryAdjustment;
import com.petcare.module.inventory.entity.InventoryItem;
import com.petcare.module.inventory.mapper.InventoryAdjustmentMapper;
import com.petcare.module.inventory.mapper.InventoryAdjustmentMapperImpl;
import com.petcare.module.inventory.repository.InventoryAdjustmentRepository;
import com.petcare.module.inventory.repository.InventoryItemRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.enums.AdjustmentReason;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-12-01, RULE-12-02, RULE-12-03 (Maker-Checker). */
@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceImplTest {

    @Mock
    private InventoryAdjustmentRepository inventoryAdjustmentRepository;
    @Mock
    private InventoryItemRepository inventoryItemRepository;
    @Mock
    private StoreService storeService;
    @Mock
    private ProductService productService;
    @Mock
    private InventoryEventRecorder inventoryEventRecorder;

    private final InventoryAdjustmentMapper inventoryAdjustmentMapper = new InventoryAdjustmentMapperImpl();

    private InventoryAdjustmentServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static ProductResponse product(UUID productId, UUID organizationId, String sku) {
        return new ProductResponse(productId, organizationId, sku, null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, "10000.00", "8000.00", true);
    }

    private static InventoryAdjustment pendingAdjustment(UUID storeId, UUID productId, int quantityAdjusted, UUID createdBy) {
        InventoryAdjustment adjustment = new InventoryAdjustment(storeId, productId, quantityAdjusted,
                AdjustmentReason.COUNT_VARIANCE, createdBy);
        adjustment.setId(UUID.randomUUID());
        return adjustment;
    }

    private static InventoryItem existingItem(UUID storeId, UUID productId, int physical, int available, int minStockLevel) {
        InventoryItem item = new InventoryItem(storeId, productId);
        item.setId(UUID.randomUUID());
        item.setQuantityPhysical(physical);
        item.setQuantityAvailable(available);
        item.setMinStockLevel(minStockLevel);
        return item;
    }

    @BeforeEach
    void setUp() {
        service = new InventoryAdjustmentServiceImpl(inventoryAdjustmentRepository, inventoryItemRepository,
                inventoryAdjustmentMapper, storeService, productService, inventoryEventRecorder);
    }

    @Test
    void createAdjustment_validReason_createsPending() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.INVENTORY_STAFF, organizationId, storeId);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryAdjustmentRepository.save(any(InventoryAdjustment.class))).thenAnswer(inv -> {
            InventoryAdjustment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        var response = service.createAdjustment(storeId,
                new CreateInventoryAdjustmentRequest(productId, -2, AdjustmentReason.DAMAGE), actor);

        assertThat(response.status()).isEqualTo(InventoryAdjustmentStatus.PENDING);
        assertThat(response.quantityAdjusted()).isEqualTo(-2);
        assertThat(response.createdBy()).isEqualTo(actor.getUserId());
    }

    @Test
    void createAdjustment_zeroQuantity_throwsBusinessRuleViolation_RULE_12_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));

        assertThatThrownBy(() -> service.createAdjustment(storeId,
                new CreateInventoryAdjustmentRequest(productId, 0, AdjustmentReason.DAMAGE),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-02"));
    }

    @Test
    void createAdjustment_crossOrgProduct_throwsBusinessRuleViolation_RULE_12_01() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, otherOrganizationId, "SKU01"));

        assertThatThrownBy(() -> service.createAdjustment(storeId,
                new CreateInventoryAdjustmentRequest(productId, -2, AdjustmentReason.DAMAGE),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-01"));
    }

    @Test
    void approveAdjustment_creatorEqualsApprover_throwsBusinessRuleViolation_RULE_12_03() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UserPrincipal maker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, -2, maker.getUserId());
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.approveAdjustment(adjustment.getId(), maker))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-03"));
        verifyNoInteractions(inventoryItemRepository);
    }

    @Test
    void approveAdjustment_differentApprover_postsToInventoryItem_setsApproved() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID makerId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, 5, makerId);
        InventoryItem item = existingItem(storeId, productId, 10, 10, 5);
        UserPrincipal checker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(item));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryAdjustmentRepository.saveAndFlush(any(InventoryAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));

        var response = service.approveAdjustment(adjustment.getId(), checker);

        assertThat(response.status()).isEqualTo(InventoryAdjustmentStatus.APPROVED);
        assertThat(response.approvedBy()).isEqualTo(checker.getUserId());
        assertThat(item.getQuantityPhysical()).isEqualTo(15);
        assertThat(item.getQuantityAvailable()).isEqualTo(15);
    }

    @Test
    void approveAdjustment_notPending_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, 5, UUID.randomUUID());
        adjustment.setStatus(InventoryAdjustmentStatus.APPROVED);
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.approveAdjustment(adjustment.getId(),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void approveAdjustment_doubleApproveRace_staleVersion_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID makerId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, 5, makerId);
        InventoryItem item = existingItem(storeId, productId, 10, 10, 5);
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(item));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryAdjustmentRepository.saveAndFlush(any(InventoryAdjustment.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryAdjustment.class, adjustment.getId()));

        assertThatThrownBy(() -> service.approveAdjustment(adjustment.getId(),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void approveAdjustment_resultingNegativeStock_throwsBusinessRuleViolation_RULE_12_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, -20, UUID.randomUUID());
        InventoryItem item = existingItem(storeId, productId, 10, 10, 5);
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.approveAdjustment(adjustment.getId(),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-02"));
    }

    @Test
    void approveAdjustment_wrongStoreScope_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, 5, UUID.randomUUID());
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.approveAdjustment(adjustment.getId(),
                principal(UserRole.STORE_MANAGER, organizationId, otherStoreId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void rejectAdjustment_creatorEqualsApprover_throwsBusinessRuleViolation_RULE_12_03() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UserPrincipal maker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, -2, maker.getUserId());
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.rejectAdjustment(adjustment.getId(), new RejectInventoryAdjustmentRequest(null), maker))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-03"));
    }

    @Test
    void rejectAdjustment_differentRejecter_setsRejected_doesNotTouchInventoryItem() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryAdjustment adjustment = pendingAdjustment(storeId, productId, -2, UUID.randomUUID());
        UserPrincipal checker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        when(inventoryAdjustmentRepository.findById(adjustment.getId())).thenReturn(Optional.of(adjustment));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(inventoryAdjustmentRepository.saveAndFlush(any(InventoryAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));

        var response = service.rejectAdjustment(adjustment.getId(), new RejectInventoryAdjustmentRequest("Sai lech"), checker);

        assertThat(response.status()).isEqualTo(InventoryAdjustmentStatus.REJECTED);
        assertThat(response.approvedBy()).isEqualTo(checker.getUserId());
        verifyNoInteractions(inventoryItemRepository);
    }
}
