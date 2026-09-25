package com.petcare.module.inventory.service;

import com.petcare.module.inventory.dto.InventoryBatchResponse;
import com.petcare.module.inventory.entity.InventoryBatch;
import com.petcare.module.inventory.mapper.InventoryBatchMapper;
import com.petcare.module.inventory.repository.InventoryBatchRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Module 12 — chi tiết theo lô (RULE-12-11 FEFO). Xem javadoc {@link InventoryBatchService}. */
@Service
@RequiredArgsConstructor
public class InventoryBatchServiceImpl implements InventoryBatchService {

    private final InventoryBatchRepository inventoryBatchRepository;
    private final InventoryBatchMapper inventoryBatchMapper;
    private final StoreService storeService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryBatchResponse> trackBatches(UUID storeId, UUID productId, boolean expiredOnly,
                                                               LocalDate expiringBefore, UserPrincipal actor,
                                                               Pageable pageable) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        LocalDate today = LocalDate.now();
        Page<InventoryBatchResponse> page = inventoryBatchRepository
                .search(storeId, productId, expiredOnly, expiringBefore, today, pageable)
                .map(batch -> inventoryBatchMapper.toResponse(batch, today));
        return PageResponse.of(page);
    }

    @Override
    @Transactional
    public InventoryBatch receiveBatch(UUID storeId, UUID productId, String batchNumber,
                                        LocalDate manufactureDate, LocalDate expiryDate, int quantity) {
        InventoryBatch batch = inventoryBatchRepository.findByStoreIdAndProductIdAndBatchNumber(storeId, productId, batchNumber)
                .orElseGet(() -> new InventoryBatch(storeId, productId, batchNumber, manufactureDate, expiryDate));
        batch.setQuantity(batch.getQuantity() + quantity);

        try {
            return inventoryBatchRepository.saveAndFlush(batch);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryBatch", batch.getId());
        }
    }

    @Override
    @Transactional
    public void issueFefo(UUID storeId, UUID productId, int quantity) {
        List<InventoryBatch> candidates = inventoryBatchRepository.findFefoCandidates(storeId, productId, LocalDate.now());

        int remaining = quantity;
        for (InventoryBatch batch : candidates) {
            if (remaining <= 0) {
                break;
            }
            int consumed = Math.min(remaining, batch.getQuantity());
            batch.setQuantity(batch.getQuantity() - consumed);
            remaining -= consumed;
            try {
                inventoryBatchRepository.saveAndFlush(batch);
            } catch (ObjectOptimisticLockingFailureException ex) {
                throw new ConcurrencyConflictException("InventoryBatch", batch.getId());
            }
        }

        if (remaining > 0) {
            // RULE-12-05 — rollup InventoryItem có thể vẫn còn số, nhưng phần còn lại đã hết hạn
            // (bị loại khỏi findFefoCandidates) nên không được phép xuất.
            throw new BusinessRuleViolationException("RULE-12-05",
                    "Không đủ tồn kho khả dụng chưa hết hạn để xuất kho theo FEFO");
        }
    }
}
