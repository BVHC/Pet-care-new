package com.petcare.module.inventory.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.dto.RejectInventoryAdjustmentRequest;
import com.petcare.module.inventory.entity.InventoryAdjustment;
import com.petcare.module.inventory.entity.InventoryItem;
import com.petcare.module.inventory.mapper.InventoryAdjustmentMapper;
import com.petcare.module.inventory.repository.InventoryAdjustmentRepository;
import com.petcare.module.inventory.repository.InventoryItemRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Module 12 — phiếu điều chỉnh tồn kho Maker-Checker. PENDING→APPROVED/REJECTED không phải 1
 * trong 19 FSM chính thức của docs/03-state-machines.md (chỉ FSM-11 StockTransferStatus thuộc
 * M12) nên không dùng StateMachineBase — guard 2-outcome thuần, cùng phong cách
 * {@code ProductServiceImpl}.
 */
@Service
@RequiredArgsConstructor
public class InventoryAdjustmentServiceImpl implements InventoryAdjustmentService {

    private final InventoryAdjustmentRepository inventoryAdjustmentRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryAdjustmentMapper inventoryAdjustmentMapper;
    private final StoreService storeService;
    private final ProductService productService;
    private final InventoryEventRecorder inventoryEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "AdjustInventory", resourceType = "InventoryAdjustment")
    public InventoryAdjustmentResponse createAdjustment(UUID storeId, CreateInventoryAdjustmentRequest request,
                                                          UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        ProductResponse product = productService.getProductForCrossModule(request.productId());
        if (!Objects.equals(product.organizationId(), organizationId)) {
            throw new BusinessRuleViolationException("RULE-12-01", "Product không thuộc Organization của Store");
        }
        if (request.quantityAdjusted() == 0) {
            throw new BusinessRuleViolationException("RULE-12-02", "quantityAdjusted không được bằng 0");
        }

        InventoryAdjustment adjustment = new InventoryAdjustment(storeId, request.productId(),
                request.quantityAdjusted(), request.reason(), actor.getUserId());
        adjustment = inventoryAdjustmentRepository.save(adjustment);

        return inventoryAdjustmentMapper.toResponse(adjustment);
    }

    @Override
    @Transactional
    @Auditable(action = "ApproveInventoryAdjustment", resourceType = "InventoryAdjustment")
    public InventoryAdjustmentResponse approveAdjustment(@AuditResourceId UUID adjustmentId, UserPrincipal actor) {
        InventoryAdjustment adjustment = inventoryAdjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryAdjustment", adjustmentId));
        UUID organizationId = storeService.getOrganizationIdForStore(adjustment.getStoreId());
        // RULE-12-03 — StoreManager (Store scope) hoặc OrgAdmin (Warehouse/toàn chuỗi scope);
        // KHÔNG InventoryStaff (assertCanManageStore không nhận role đó).
        RoleScopeGuard.assertCanManageStore(actor, organizationId, adjustment.getStoreId());

        if (adjustment.getStatus() != InventoryAdjustmentStatus.PENDING) {
            throw new ConcurrencyConflictException("InventoryAdjustment", adjustmentId);
        }
        // RULE-12-03 — Maker-Checker: created_by != approved_by.
        if (Objects.equals(adjustment.getCreatedBy(), actor.getUserId())) {
            throw new BusinessRuleViolationException("RULE-12-03",
                    "MAKER_CHECKER_VIOLATION: created_by không được trùng approved_by");
        }

        UUID adjustmentStoreId = adjustment.getStoreId();
        UUID adjustmentProductId = adjustment.getProductId();
        InventoryItem item = inventoryItemRepository
                .findByStoreIdAndProductId(adjustmentStoreId, adjustmentProductId)
                .orElseGet(() -> new InventoryItem(adjustmentStoreId, adjustmentProductId));

        int newPhysical = item.getQuantityPhysical() + adjustment.getQuantityAdjusted();
        int newAvailable = item.getQuantityAvailable() + adjustment.getQuantityAdjusted();
        if (newPhysical < 0 || newAvailable < 0) {
            throw new BusinessRuleViolationException("RULE-12-02", "Điều chỉnh làm âm tồn kho");
        }
        int beforeAvailable = item.getQuantityAvailable();
        item.setQuantityPhysical(newPhysical);
        item.setQuantityAvailable(newAvailable);
        try {
            item = inventoryItemRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryItem", item.getId());
        }

        adjustment.setStatus(InventoryAdjustmentStatus.APPROVED);
        adjustment.setApprovedBy(actor.getUserId());
        adjustment.setDecidedAt(LocalDateTime.now());
        try {
            adjustment = inventoryAdjustmentRepository.saveAndFlush(adjustment);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryAdjustment", adjustmentId);
        }

        String sku = productService.getProductForCrossModule(adjustment.getProductId()).sku();
        inventoryEventRecorder.recordAdjustmentEvent(adjustment, sku, "InventoryAdjusted");
        inventoryEventRecorder.maybeRecordLowStockAlert(item, beforeAvailable, sku);

        return inventoryAdjustmentMapper.toResponse(adjustment);
    }

    @Override
    @Transactional
    @Auditable(action = "RejectInventoryAdjustment", resourceType = "InventoryAdjustment")
    public InventoryAdjustmentResponse rejectAdjustment(@AuditResourceId UUID adjustmentId,
                                                          RejectInventoryAdjustmentRequest request, UserPrincipal actor) {
        InventoryAdjustment adjustment = inventoryAdjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryAdjustment", adjustmentId));
        UUID organizationId = storeService.getOrganizationIdForStore(adjustment.getStoreId());
        RoleScopeGuard.assertCanManageStore(actor, organizationId, adjustment.getStoreId());

        if (adjustment.getStatus() != InventoryAdjustmentStatus.PENDING) {
            throw new ConcurrencyConflictException("InventoryAdjustment", adjustmentId);
        }
        // RULE-12-03 — đối xứng approve: người từ chối cũng không được là người tạo phiếu.
        if (Objects.equals(adjustment.getCreatedBy(), actor.getUserId())) {
            throw new BusinessRuleViolationException("RULE-12-03",
                    "MAKER_CHECKER_VIOLATION: created_by không được trùng approved_by");
        }

        adjustment.setStatus(InventoryAdjustmentStatus.REJECTED);
        adjustment.setApprovedBy(actor.getUserId());
        adjustment.setDecidedAt(LocalDateTime.now());
        try {
            adjustment = inventoryAdjustmentRepository.saveAndFlush(adjustment);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryAdjustment", adjustmentId);
        }

        String sku = productService.getProductForCrossModule(adjustment.getProductId()).sku();
        inventoryEventRecorder.recordAdjustmentEvent(adjustment, sku, "InventoryAdjustmentRejected");

        return inventoryAdjustmentMapper.toResponse(adjustment);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryAdjustmentResponse> listAdjustments(UUID storeId, InventoryAdjustmentStatus status,
                                                                       UserPrincipal actor, Pageable pageable) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        Page<InventoryAdjustment> page = status == null
                ? inventoryAdjustmentRepository.findAllByStoreId(storeId, pageable)
                : inventoryAdjustmentRepository.findAllByStoreIdAndStatus(storeId, status, pageable);
        return PageResponse.of(page.map(inventoryAdjustmentMapper::toResponse));
    }
}
