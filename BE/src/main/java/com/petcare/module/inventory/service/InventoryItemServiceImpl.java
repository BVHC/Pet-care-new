package com.petcare.module.inventory.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.inventory.dto.CountInventoryRequest;
import com.petcare.module.inventory.dto.CountInventoryResponse;
import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.IssueInventoryRequest;
import com.petcare.module.inventory.dto.ReceiveInventoryRequest;
import com.petcare.module.inventory.entity.InventoryItem;
import com.petcare.module.inventory.entity.InventoryReservation;
import com.petcare.module.inventory.mapper.InventoryItemMapper;
import com.petcare.module.inventory.repository.InventoryItemRepository;
import com.petcare.module.inventory.repository.InventoryReservationRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.AdjustmentReason;
import com.petcare.platform.enums.ReservationStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Module 12 — rollup tổng tồn kho (TrackInventory/ReceiveInventory/IssueInventory/
 * CountInventory). Xem javadoc {@link InventoryItemService}.
 */
@Service
@RequiredArgsConstructor
public class InventoryItemServiceImpl implements InventoryItemService {

    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryItemMapper inventoryItemMapper;
    private final InventoryBatchService inventoryBatchService;
    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final StoreService storeService;
    private final ProductService productService;
    private final InventoryEventRecorder inventoryEventRecorder;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryItemResponse> trackInventory(UUID storeId, UUID productId, boolean lowOnly,
                                                                UserPrincipal actor, Pageable pageable) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        Page<InventoryItemResponse> page = inventoryItemRepository.search(storeId, productId, lowOnly, pageable)
                .map(item -> {
                    String sku = productService.getProductForCrossModule(item.getProductId()).sku();
                    return inventoryItemMapper.toResponse(item, sku);
                });
        return PageResponse.of(page);
    }

    @Override
    @Transactional
    @Auditable(action = "ReceiveInventory", resourceType = "InventoryItem")
    public InventoryItemResponse receiveInventory(UUID storeId, ReceiveInventoryRequest request, UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        ProductResponse product = assertProductInOrganization(request.productId(), organizationId);

        inventoryBatchService.receiveBatch(storeId, request.productId(), request.batchNumber(),
                request.manufactureDate(), request.expiryDate(), request.quantity());

        InventoryItem item = inventoryItemRepository.findByStoreIdAndProductId(storeId, request.productId())
                .orElseGet(() -> new InventoryItem(storeId, request.productId()));
        boolean isNew = item.getId() == null;
        int beforeAvailable = item.getQuantityAvailable();
        item.setQuantityPhysical(item.getQuantityPhysical() + request.quantity());
        item.setQuantityAvailable(item.getQuantityAvailable() + request.quantity());
        try {
            item = inventoryItemRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryItem", item.getId());
        } catch (DataIntegrityViolationException ex) {
            // Race giữa findByStoreIdAndProductId() và save() khi item chưa từng tồn tại — 2 lệnh
            // ReceiveInventory đầu tiên đồng thời cùng Store+Product đều thấy "chưa có", UNIQUE
            // uq_inventory_items_store_product ở DB là guard thật (cùng pattern ProductServiceImpl).
            if (isNew) {
                throw new ConcurrencyConflictException("InventoryItem", request.productId());
            }
            throw ex;
        }

        inventoryEventRecorder.maybeRecordLowStockAlert(item, beforeAvailable, product.sku());

        return inventoryItemMapper.toResponse(item, product.sku());
    }

    @Override
    @Transactional
    @Auditable(action = "IssueInventory", resourceType = "InventoryItem")
    public InventoryItemResponse issueInventory(UUID storeId, IssueInventoryRequest request, UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        ProductResponse product = assertProductInOrganization(request.productId(), organizationId);

        InventoryItem item = inventoryItemRepository.findByStoreIdAndProductId(storeId, request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", request.productId()));

        // RULE-12-05 — fast-path: từ chối sớm nếu rollup rõ ràng không đủ, trước khi chạm tới
        // bảng lô. Guard thật (kể cả trường hợp phần còn lại đã hết hạn) nằm ở issueFefo().
        if (item.getQuantityAvailable() < request.quantity()) {
            throw new BusinessRuleViolationException("RULE-12-05", "Không đủ tồn kho khả dụng");
        }

        inventoryBatchService.issueFefo(storeId, request.productId(), request.quantity());

        int beforeAvailable = item.getQuantityAvailable();
        item.setQuantityPhysical(item.getQuantityPhysical() - request.quantity());
        item.setQuantityAvailable(item.getQuantityAvailable() - request.quantity());
        // saveAndFlush — chính optimistic-lock trên dòng InventoryItem này chặn oversell khi 2 lệnh
        // IssueInventory chạy đồng thời (mỗi lệnh có thể thấy "đủ hàng" trước khi lệnh kia commit);
        // saveAndFlush ép flush ngay để bắt được conflict tại đây, cùng pattern StoreServiceImpl.updateStore.
        item = saveItemOrThrow(item);

        inventoryEventRecorder.maybeRecordLowStockAlert(item, beforeAvailable, product.sku());

        return inventoryItemMapper.toResponse(item, product.sku());
    }

    @Override
    @Transactional
    public void reserveStock(UUID storeId, UUID productId, int quantity, UUID orderId, LocalDateTime expiresAt) {
        InventoryItem item = inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
        if (item.getQuantityAvailable() < quantity) {
            throw new BusinessRuleViolationException("RULE-14-02", "Không đủ tồn kho khả dụng để giữ chỗ");
        }

        int beforeAvailable = item.getQuantityAvailable();
        item.setQuantityReserved(item.getQuantityReserved() + quantity);
        item.setQuantityAvailable(beforeAvailable - quantity);
        item = saveItemOrThrow(item);

        inventoryEventRecorder.maybeRecordLowStockAlert(item, beforeAvailable,
                productService.getProductForCrossModule(productId).sku());

        inventoryReservationRepository.save(new InventoryReservation(orderId, item.getId(), quantity, expiresAt));
    }

    @Override
    @Transactional
    public void releaseReservation(UUID orderId) {
        List<InventoryReservation> held = inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD);
        for (InventoryReservation reservation : held) {
            // Conditional update chống double-release khi CancelOrder và ProcessOrderTimeout race
            // nhau: 0 dòng bị ảnh hưởng nghĩa là reservation đã được lệnh khác release trước —
            // bỏ qua, không cộng lại quantityAvailable lần 2.
            if (inventoryReservationRepository.releaseIfHeld(reservation.getId()) == 0) {
                continue;
            }
            InventoryItem item = inventoryItemRepository.findById(reservation.getInventoryItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", reservation.getInventoryItemId()));
            item.setQuantityReserved(item.getQuantityReserved() - reservation.getQuantity());
            item.setQuantityAvailable(item.getQuantityAvailable() + reservation.getQuantity());
            saveItemOrThrow(item);
        }
    }

    @Override
    @Transactional
    public void deductPhysicalForOrder(UUID storeId, UUID productId, int quantity) {
        InventoryItem item = inventoryItemRepository.findByStoreIdAndProductId(storeId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", productId));
        if (item.getQuantityAvailable() < quantity) {
            throw new BusinessRuleViolationException("RULE-14-02", "Không đủ tồn kho khả dụng");
        }

        inventoryBatchService.issueFefo(storeId, productId, quantity);

        int beforeAvailable = item.getQuantityAvailable();
        item.setQuantityPhysical(item.getQuantityPhysical() - quantity);
        item.setQuantityAvailable(beforeAvailable - quantity);
        item = saveItemOrThrow(item);

        inventoryEventRecorder.maybeRecordLowStockAlert(item, beforeAvailable,
                productService.getProductForCrossModule(productId).sku());
    }

    @Override
    @Transactional
    public void commitReservation(UUID orderId) {
        List<InventoryReservation> held = inventoryReservationRepository.findAllByOrderIdAndStatus(orderId, ReservationStatus.HELD);
        for (InventoryReservation reservation : held) {
            // Conditional update chống double-commit nếu bị gọi lại — cùng idiom releaseReservation().
            if (inventoryReservationRepository.commitIfHeld(reservation.getId()) == 0) {
                continue;
            }
            InventoryItem item = inventoryItemRepository.findById(reservation.getInventoryItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", reservation.getInventoryItemId()));
            inventoryBatchService.issueFefo(item.getStoreId(), item.getProductId(), reservation.getQuantity());
            item.setQuantityPhysical(item.getQuantityPhysical() - reservation.getQuantity());
            item.setQuantityReserved(item.getQuantityReserved() - reservation.getQuantity());
            saveItemOrThrow(item);
            // quantityAvailable KHÔNG đổi — đã trừ sẵn lúc reserveStock; physical và reserved giảm
            // cùng lượng nên available bảo toàn, ngưỡng low-stock không thể lật trạng thái ở đây.
        }
    }

    private InventoryItem saveItemOrThrow(InventoryItem item) {
        try {
            return inventoryItemRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("InventoryItem", item.getId());
        }
    }

    @Override
    @Transactional
    @Auditable(action = "CountInventory", resourceType = "InventoryItem")
    public CountInventoryResponse countInventory(UUID storeId, CountInventoryRequest request, UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);
        assertProductInOrganization(request.productId(), organizationId);

        InventoryItem item = inventoryItemRepository.findByStoreIdAndProductId(storeId, request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("InventoryItem", request.productId()));

        int variance = request.countedQuantity() - item.getQuantityPhysical();
        if (variance == 0) {
            // RULE-12-02, docs/api/inventory-v1.md §E Q5 DECIDED — kiểm kê khớp: không lưu gì.
            return new CountInventoryResponse(storeId, request.productId(), request.countedQuantity(), 0, null);
        }

        CreateInventoryAdjustmentRequest adjustmentRequest = new CreateInventoryAdjustmentRequest(
                request.productId(), variance, AdjustmentReason.COUNT_VARIANCE);
        InventoryAdjustmentResponse adjustment = inventoryAdjustmentService.createAdjustment(storeId, adjustmentRequest, actor);

        return new CountInventoryResponse(storeId, request.productId(), request.countedQuantity(), variance,
                adjustment.adjustmentId());
    }

    private ProductResponse assertProductInOrganization(UUID productId, UUID organizationId) {
        ProductResponse product = productService.getProductForCrossModule(productId);
        if (!Objects.equals(product.organizationId(), organizationId)) {
            throw new BusinessRuleViolationException("RULE-12-01", "Product không thuộc Organization của Store");
        }
        return product;
    }
}
