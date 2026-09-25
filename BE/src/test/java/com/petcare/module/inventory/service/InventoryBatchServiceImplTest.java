package com.petcare.module.inventory.service;

import com.petcare.module.inventory.entity.InventoryBatch;
import com.petcare.module.inventory.mapper.InventoryBatchMapper;
import com.petcare.module.inventory.mapper.InventoryBatchMapperImpl;
import com.petcare.module.inventory.repository.InventoryBatchRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RULE-12-11 (FEFO). {@link InventoryBatchRepository#findFefoCandidates} đã trả list SẮP XẾP
 * sẵn theo expiryDate ASC (đảm bảo bởi query + mặc định NULLS LAST của Postgres, xác nhận ở
 * {@code InventoryFefoIssueIT} chạy trên DB thật) — test ở đây chỉ xác nhận Service tiêu thụ
 * greedy đúng theo THỨ TỰ list được trả về, không tự re-sort.
 */
@ExtendWith(MockitoExtension.class)
class InventoryBatchServiceImplTest {

    @Mock
    private InventoryBatchRepository inventoryBatchRepository;
    @Mock
    private StoreService storeService;

    private final InventoryBatchMapper inventoryBatchMapper = new InventoryBatchMapperImpl();

    private InventoryBatchServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static InventoryBatch batch(UUID storeId, UUID productId, String batchNumber, int quantity, LocalDate expiryDate) {
        InventoryBatch b = new InventoryBatch(storeId, productId, batchNumber, null, expiryDate);
        b.setId(UUID.randomUUID());
        b.setQuantity(quantity);
        return b;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new InventoryBatchServiceImpl(inventoryBatchRepository, inventoryBatchMapper, storeService);
    }

    @Test
    void issueFefo_multipleBatches_consumesEarliestExpiryFirst_RULE_12_11() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryBatch earlier = batch(storeId, productId, "B1", 5, LocalDate.now().plusDays(5));
        InventoryBatch later = batch(storeId, productId, "B2", 20, LocalDate.now().plusDays(30));
        when(inventoryBatchRepository.findFefoCandidates(eq(storeId), eq(productId), any(LocalDate.class)))
                .thenReturn(List.of(earlier, later));
        when(inventoryBatchRepository.saveAndFlush(any(InventoryBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.issueFefo(storeId, productId, 8);

        assertThat(earlier.getQuantity()).isZero();
        assertThat(later.getQuantity()).isEqualTo(17);
    }

    @Test
    void issueFefo_exactlyOneBatchCovers_doesNotTouchLaterBatches() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryBatch earlier = batch(storeId, productId, "B1", 10, LocalDate.now().plusDays(5));
        InventoryBatch later = batch(storeId, productId, "B2", 10, LocalDate.now().plusDays(30));
        when(inventoryBatchRepository.findFefoCandidates(eq(storeId), eq(productId), any(LocalDate.class)))
                .thenReturn(List.of(earlier, later));
        when(inventoryBatchRepository.saveAndFlush(any(InventoryBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.issueFefo(storeId, productId, 10);

        assertThat(earlier.getQuantity()).isZero();
        assertThat(later.getQuantity()).isEqualTo(10);
    }

    @Test
    void issueFefo_onlyExpiredBatchesRemain_throwsBusinessRuleViolation_RULE_12_05() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        // findFefoCandidates() đã lọc bỏ lô hết hạn ở query — mô phỏng bằng list rỗng (rollup
        // InventoryItem vẫn còn số nhưng toàn bộ nằm ở lô đã hết hạn, không được trả về).
        when(inventoryBatchRepository.findFefoCandidates(eq(storeId), eq(productId), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.issueFefo(storeId, productId, 5))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-05"));
    }

    @Test
    void issueFefo_candidatesInsufficientEvenCombined_throwsBusinessRuleViolation_RULE_12_05() {
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryBatch only = batch(storeId, productId, "B1", 3, LocalDate.now().plusDays(5));
        when(inventoryBatchRepository.findFefoCandidates(eq(storeId), eq(productId), any(LocalDate.class)))
                .thenReturn(List.of(only));
        when(inventoryBatchRepository.saveAndFlush(any(InventoryBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.issueFefo(storeId, productId, 10))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-12-05"));
        assertThat(only.getQuantity()).isZero();
    }

    @Test
    void trackBatches_wrongScope_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.trackBatches(storeId, null, false, null,
                principal(UserRole.INVENTORY_STAFF, organizationId, otherStoreId), pageable))
                .isInstanceOf(AccessDeniedScopeException.class);
    }
}
