package com.petcare.module.inventory.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.inventory.dto.CountInventoryRequest;
import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.dto.IssueInventoryRequest;
import com.petcare.module.inventory.dto.ReceiveInventoryRequest;
import com.petcare.module.inventory.entity.InventoryItem;
import com.petcare.module.inventory.entity.InventoryReservation;
import com.petcare.module.inventory.mapper.InventoryItemMapper;
import com.petcare.module.inventory.mapper.InventoryItemMapperImpl;
import com.petcare.module.inventory.repository.InventoryItemRepository;
import com.petcare.module.inventory.repository.InventoryReservationRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.enums.AdjustmentReason;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.ReservationStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-12-01, RULE-12-02, RULE-12-05. */
@ExtendWith(MockitoExtension.class)
class InventoryItemServiceImplTest {

    @Mock
    private InventoryItemRepository inventoryItemRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private InventoryBatchService inventoryBatchService;
    @Mock
    private InventoryAdjustmentService inventoryAdjustmentService;
    @Mock
    private StoreService storeService;
    @Mock
    private ProductService productService;
    @Mock
    private InventoryEventRecorder inventoryEventRecorder;

    private final InventoryItemMapper inventoryItemMapper = new InventoryItemMapperImpl();

    private InventoryItemServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static ProductResponse product(UUID productId, UUID organizationId, String sku) {
        return new ProductResponse(productId, organizationId, sku, null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, "10000.00", "8000.00", true);
    }

    @BeforeEach
    void setUp() {
        service = new InventoryItemServiceImpl(inventoryItemRepository, inventoryReservationRepository,
                inventoryItemMapper, inventoryBatchService, inventoryAdjustmentService, storeService, productService,
                inventoryEventRecorder);
    }

    @Test
    void receiveInventory_newBatchAndItem_createsRows_increasesAvailable() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.empty());
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> {
            InventoryItem item = invocation.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });

        var response = service.receiveInventory(storeId,
                new ReceiveInventoryRequest(productId, 10, "BATCH01", null, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.quantityPhysical()).isEqualTo(10);
        assertThat(response.quantityAvailable()).isEqualTo(10);
        assertThat(response.sku()).isEqualTo("SKU01");
        verify(inventoryBatchService).receiveBatch(storeId, productId, "BATCH01", null, null, 10);
    }

    @Test
    void receiveInventory_crossOrgProduct_throwsBusinessRuleViolation_RULE_12_01() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, otherOrganizationId, "SKU01"));

        assertThatThrownBy(() -> service.receiveInventory(storeId,
                new ReceiveInventoryRequest(productId, 10, "BATCH01", null, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-01"));
        verifyNoInteractions(inventoryBatchService);
    }

    @Test
    void receiveInventory_wrongStoreScope_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.receiveInventory(storeId,
                new ReceiveInventoryRequest(productId, 10, "BATCH01", null, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, otherStoreId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void issueInventory_sufficientStock_decreasesAvailable() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 0, 20, 5);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.issueInventory(storeId, new IssueInventoryRequest(productId, 8, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.quantityPhysical()).isEqualTo(12);
        assertThat(response.quantityAvailable()).isEqualTo(12);
        verify(inventoryBatchService).issueFefo(storeId, productId, 8);
    }

    @Test
    void issueInventory_insufficientAvailable_throwsBusinessRuleViolation_RULE_12_05() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 5, 0, 5, 5);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.issueInventory(storeId, new IssueInventoryRequest(productId, 10, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-05"));
        verifyNoInteractions(inventoryBatchService);
    }

    @Test
    void issueInventory_notReceivedYet_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueInventory(storeId, new IssueInventoryRequest(productId, 1, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void issueInventory_staleVersion_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 0, 20, 5);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItem.class, existing.getId()));

        assertThatThrownBy(() -> service.issueInventory(storeId, new IssueInventoryRequest(productId, 5, null),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void trackInventory_belowMinStockLevel_lowStockTrue() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem lowItem = existingItem(storeId, productId, 3, 0, 3, 5);
        Pageable pageable = PageRequest.of(0, 20);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(inventoryItemRepository.search(eq(storeId), eq(null), eq(false), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(lowItem)));
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));

        var page = service.trackInventory(storeId, null, false,
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId), pageable);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).lowStock()).isTrue();
    }

    @Test
    void countInventory_zeroVariance_returnsNullAdjustmentId_noPersist() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 10, 0, 10, 5);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));

        var response = service.countInventory(storeId, new CountInventoryRequest(productId, 10),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.variance()).isZero();
        assertThat(response.adjustmentId()).isNull();
        verifyNoInteractions(inventoryAdjustmentService);
    }

    @Test
    void countInventory_nonZeroVariance_createsPendingAdjustment_reasonCountVariance() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID adjustmentId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 10, 0, 10, 5);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.INVENTORY_STAFF, organizationId, storeId);
        when(inventoryAdjustmentService.createAdjustment(eq(storeId),
                eq(new CreateInventoryAdjustmentRequest(productId, -3, AdjustmentReason.COUNT_VARIANCE)), eq(actor)))
                .thenReturn(new InventoryAdjustmentResponse(adjustmentId, storeId, productId, -3,
                        AdjustmentReason.COUNT_VARIANCE, InventoryAdjustmentStatus.PENDING, actor.getUserId(), null, null, null));

        var response = service.countInventory(storeId, new CountInventoryRequest(productId, 7), actor);

        assertThat(response.variance()).isEqualTo(-3);
        assertThat(response.adjustmentId()).isEqualTo(adjustmentId);
    }

    @Test
    void reserveStock_sufficientAvailable_increasesReserved_createsReservation() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 0, 20, 5);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(15);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, UUID.randomUUID(), "SKU01"));

        service.reserveStock(storeId, productId, 8, orderId, expiresAt);

        assertThat(existing.getQuantityReserved()).isEqualTo(8);
        assertThat(existing.getQuantityAvailable()).isEqualTo(12);
        verify(inventoryReservationRepository).save(any(InventoryReservation.class));
    }

    @Test
    void reserveStock_insufficientAvailable_throwsBusinessRuleViolation_RULE_14_02() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 5, 0, 5, 5);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.reserveStock(storeId, productId, 10, UUID.randomUUID(), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-02"));
        verifyNoInteractions(inventoryReservationRepository);
    }

    @Test
    void reserveStock_staleVersion_throwsConcurrencyConflict() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 0, 20, 5);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItem.class, existing.getId()));

        assertThatThrownBy(() -> service.reserveStock(storeId, productId, 5, UUID.randomUUID(), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void releaseReservation_held_decreasesReserved_increasesAvailable_marksReleased() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 8, 12, 5);
        InventoryReservation reservation = new InventoryReservation(orderId, existing.getId(), 8, LocalDateTime.now());
        reservation.setId(UUID.randomUUID());
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD))
                .thenReturn(List.of(reservation));
        when(inventoryReservationRepository.releaseIfHeld(reservation.getId())).thenReturn(1);
        when(inventoryItemRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.releaseReservation(orderId);

        assertThat(existing.getQuantityReserved()).isEqualTo(0);
        assertThat(existing.getQuantityAvailable()).isEqualTo(20);
    }

    @Test
    void releaseReservation_alreadyReleasedByRace_noOp() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 8, 12, 5);
        InventoryReservation reservation = new InventoryReservation(orderId, existing.getId(), 8, LocalDateTime.now());
        reservation.setId(UUID.randomUUID());
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD))
                .thenReturn(List.of(reservation));
        when(inventoryReservationRepository.releaseIfHeld(reservation.getId())).thenReturn(0);

        service.releaseReservation(orderId);

        verify(inventoryItemRepository, never()).findById(any());
    }

    @Test
    void releaseReservation_noneHeld_noOp() {
        UUID orderId = UUID.randomUUID();
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD)).thenReturn(List.of());

        service.releaseReservation(orderId);

        verifyNoInteractions(inventoryItemRepository);
    }

    @Test
    void deductPhysicalForOrder_sufficientStock_decreasesPhysicalAndAvailable() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 0, 20, 5);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, UUID.randomUUID(), "SKU01"));

        service.deductPhysicalForOrder(storeId, productId, 6);

        assertThat(existing.getQuantityPhysical()).isEqualTo(14);
        assertThat(existing.getQuantityAvailable()).isEqualTo(14);
        verify(inventoryBatchService).issueFefo(storeId, productId, 6);
    }

    @Test
    void deductPhysicalForOrder_insufficientAvailable_throwsBusinessRuleViolation_RULE_14_02() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 5, 0, 5, 5);
        when(inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.deductPhysicalForOrder(storeId, productId, 10))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-02"));
        verifyNoInteractions(inventoryBatchService);
    }

    @Test
    void commitReservation_held_decreasesPhysicalAndReserved_availableUnchanged_marksCommitted() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 8, 12, 5);
        InventoryReservation reservation = new InventoryReservation(orderId, existing.getId(), 8, LocalDateTime.now());
        reservation.setId(UUID.randomUUID());
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD))
                .thenReturn(List.of(reservation));
        when(inventoryReservationRepository.commitIfHeld(reservation.getId())).thenReturn(1);
        when(inventoryItemRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(inventoryItemRepository.saveAndFlush(any(InventoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.commitReservation(orderId);

        assertThat(existing.getQuantityPhysical()).isEqualTo(12);
        assertThat(existing.getQuantityReserved()).isEqualTo(0);
        assertThat(existing.getQuantityAvailable()).isEqualTo(12);
        verify(inventoryBatchService).issueFefo(storeId, productId, 8);
    }

    @Test
    void commitReservation_alreadyCommittedByRace_noOp() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryItem existing = existingItem(storeId, productId, 20, 8, 12, 5);
        InventoryReservation reservation = new InventoryReservation(orderId, existing.getId(), 8, LocalDateTime.now());
        reservation.setId(UUID.randomUUID());
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD))
                .thenReturn(List.of(reservation));
        when(inventoryReservationRepository.commitIfHeld(reservation.getId())).thenReturn(0);

        service.commitReservation(orderId);

        verify(inventoryItemRepository, never()).findById(any());
        verifyNoInteractions(inventoryBatchService);
    }

    @Test
    void commitReservation_noneHeld_noOp() {
        UUID orderId = UUID.randomUUID();
        when(inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD)).thenReturn(List.of());

        service.commitReservation(orderId);

        verifyNoInteractions(inventoryItemRepository, inventoryBatchService);
    }

    private static InventoryItem existingItem(UUID storeId, UUID productId, int physical, int reserved,
                                               int available, int minStockLevel) {
        InventoryItem item = new InventoryItem(storeId, productId);
        item.setId(UUID.randomUUID());
        item.setQuantityPhysical(physical);
        item.setQuantityReserved(reserved);
        item.setQuantityAvailable(available);
        item.setMinStockLevel(minStockLevel);
        return item;
    }
}
