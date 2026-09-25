package com.petcare.module.procurement.service;

import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.organization.service.StoreService;
import com.petcare.module.procurement.dto.CreatePurchaseOrderRequest;
import com.petcare.module.procurement.dto.PurchaseOrderLineResponse;
import com.petcare.module.procurement.dto.PurchaseOrderResponse;
import com.petcare.module.procurement.entity.PurchaseOrder;
import com.petcare.module.procurement.entity.PurchaseOrderLine;
import com.petcare.module.procurement.entity.PurchaseRequest;
import com.petcare.module.procurement.entity.PurchaseRequestLine;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.module.procurement.mapper.PurchaseOrderMapper;
import com.petcare.module.procurement.repository.PurchaseOrderLineRepository;
import com.petcare.module.procurement.repository.PurchaseOrderRepository;
import com.petcare.module.procurement.repository.PurchaseRequestLineRepository;
import com.petcare.module.procurement.repository.PurchaseRequestRepository;
import com.petcare.module.procurement.repository.SupplierRepository;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.PurchaseRequestStatus;
import com.petcare.platform.enums.SupplierStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Module 13 — PurchaseOrder (RULE-13-04). Phạm vi task chỉ dừng ở {@code ISSUED} — không FSM call
 * (khởi tạo, cùng lý do {@code createAdjustment}/{@code createStore} không gọi FSM). Đọc
 * {@code PurchaseRequestRepository}/{@code SupplierRepository} trực tiếp — cùng module, không cần
 * qua service interface (khác {@code ProductService}/{@code StoreService} thật sự cross-module).
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestLineRepository purchaseRequestLineRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final StoreService storeService;
    private final ProductService productService;
    private final ProcurementEventRecorder procurementEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "CreatePurchaseOrder", resourceType = "PurchaseOrder")
    public PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(request.purchaseRequestId())
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseRequest", request.purchaseRequestId()));
        UUID organizationId = storeService.getOrganizationIdForStore(purchaseRequest.getStoreId());
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, purchaseRequest.getStoreId());

        if (purchaseRequest.getStatus() != PurchaseRequestStatus.APPROVED) {
            throw new BusinessRuleViolationException("RULE-13-04", "PurchaseRequest phải ở trạng thái APPROVED");
        }

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", request.supplierId()));
        if (!Objects.equals(supplier.getOrganizationId(), organizationId)) {
            throw new BusinessRuleViolationException("RULE-13-04", "Supplier không thuộc Organization của PurchaseRequest");
        }
        if (supplier.getStatus() != SupplierStatus.ACTIVE) {
            throw new BusinessRuleViolationException("RULE-13-04", "Supplier phải ở trạng thái ACTIVE");
        }

        List<PurchaseRequestLine> requestLines = purchaseRequestLineRepository.findAllByPurchaseRequestId(purchaseRequest.getId());

        String poNumber = generateNumber("PO");
        PurchaseOrder purchaseOrder = new PurchaseOrder(poNumber, purchaseRequest.getId(),
                purchaseRequest.getStoreId(), supplier.getId(), actor.getUserId());
        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<PurchaseOrderLine> orderLines = new ArrayList<>();
        for (PurchaseRequestLine requestLine : requestLines) {
            orderLines.add(new PurchaseOrderLine(purchaseOrder.getId(), requestLine.getProductId(),
                    requestLine.getRequestedQuantity(), requestLine.getEstimatedUnitPrice()));
            totalAmount = totalAmount.add(requestLine.getEstimatedUnitPrice()
                    .multiply(BigDecimal.valueOf(requestLine.getRequestedQuantity())));
        }
        purchaseOrderLineRepository.saveAll(orderLines);

        purchaseOrder.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        try {
            purchaseOrder = purchaseOrderRepository.saveAndFlush(purchaseOrder);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("PurchaseOrder", purchaseOrder.getId());
        }

        procurementEventRecorder.recordPurchaseOrderCreated(purchaseOrder, orderLines.size());

        return buildResponse(purchaseOrder, supplier.getName(), orderLines);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderResponse getPurchaseOrder(UUID orderId, UserPrincipal actor) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", orderId));
        UUID organizationId = storeService.getOrganizationIdForStore(purchaseOrder.getStoreId());
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, purchaseOrder.getStoreId());

        Supplier supplier = supplierRepository.findById(purchaseOrder.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", purchaseOrder.getSupplierId()));
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findAllByPurchaseOrderId(orderId);

        return buildResponse(purchaseOrder, supplier.getName(), lines);
    }

    private PurchaseOrderResponse buildResponse(PurchaseOrder purchaseOrder, String supplierName, List<PurchaseOrderLine> lines) {
        List<PurchaseOrderLineResponse> lineResponses = lines.stream().map(line -> {
            String sku = productService.getProductForCrossModule(line.getProductId()).sku();
            return new PurchaseOrderLineResponse(line.getProductId(), sku, line.getOrderedQuantity(),
                    line.getReceivedQuantity(), line.getUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }).toList();
        return purchaseOrderMapper.toResponse(purchaseOrder, supplierName, lineResponses);
    }

    private String generateNumber(String prefix) {
        String datePart = LocalDateTime.now().toLocalDate().toString().replace("-", "");
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "-" + datePart + "-" + randomPart;
    }
}
