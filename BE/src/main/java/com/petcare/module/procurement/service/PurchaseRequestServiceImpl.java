package com.petcare.module.procurement.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.organization.service.StoreService;
import com.petcare.module.procurement.dto.CreatePurchaseRequestRequest;
import com.petcare.module.procurement.dto.PurchaseRequestLineItem;
import com.petcare.module.procurement.dto.PurchaseRequestLineResponse;
import com.petcare.module.procurement.dto.PurchaseRequestResponse;
import com.petcare.module.procurement.dto.RejectPurchaseRequestRequest;
import com.petcare.module.procurement.entity.PurchaseRequest;
import com.petcare.module.procurement.entity.PurchaseRequestLine;
import com.petcare.module.procurement.fsm.PurchaseRequestTransitionHandler;
import com.petcare.module.procurement.mapper.PurchaseRequestMapper;
import com.petcare.module.procurement.repository.PurchaseRequestLineRepository;
import com.petcare.module.procurement.repository.PurchaseRequestRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.PurchaseRequestStatus;
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
 * Module 13 — PurchaseRequest (FSM-12, RULE-13-01→03). PENDING/DRAFT→SUBMITTED→APPROVED/REJECTED/
 * CANCELLED là FSM thật (khác {@code InventoryAdjustment} ở Module 12) nên guard nghiệp vụ
 * (RULE-ID, Maker-Checker) luôn chạy TRƯỚC {@link PurchaseRequestTransitionHandler#validateTransition}.
 */
@Service
@RequiredArgsConstructor
public class PurchaseRequestServiceImpl implements PurchaseRequestService {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestLineRepository purchaseRequestLineRepository;
    private final PurchaseRequestMapper purchaseRequestMapper;
    private final PurchaseRequestTransitionHandler purchaseRequestTransitionHandler;
    private final StoreService storeService;
    private final ProductService productService;
    private final ProcurementEventRecorder procurementEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "CreatePurchaseRequest", resourceType = "PurchaseRequest")
    public PurchaseRequestResponse createPurchaseRequest(UUID storeId, CreatePurchaseRequestRequest request, UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(storeId);
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, storeId);

        for (PurchaseRequestLineItem line : request.lines()) {
            ProductResponse product = productService.getProductForCrossModule(line.productId());
            if (!Objects.equals(product.organizationId(), organizationId)) {
                throw new BusinessRuleViolationException("RULE-13-01", "Product không thuộc Organization của Store");
            }
        }

        String requestNumber = generateNumber("PR");
        PurchaseRequest purchaseRequest = new PurchaseRequest(requestNumber, storeId, actor.getUserId());
        purchaseRequest = purchaseRequestRepository.save(purchaseRequest);

        BigDecimal estimatedCost = BigDecimal.ZERO;
        List<PurchaseRequestLine> lines = new ArrayList<>();
        for (PurchaseRequestLineItem item : request.lines()) {
            BigDecimal price = new BigDecimal(item.estimatedUnitPrice());
            lines.add(new PurchaseRequestLine(purchaseRequest.getId(), item.productId(), item.requestedQuantity(),
                    price, item.recommendedSupplierName()));
            estimatedCost = estimatedCost.add(price.multiply(BigDecimal.valueOf(item.requestedQuantity())));
        }
        purchaseRequestLineRepository.saveAll(lines);

        procurementEventRecorder.recordPurchaseRequestCreated(purchaseRequest, lines.size(),
                estimatedCost.setScale(2, RoundingMode.HALF_UP).toPlainString());

        return buildResponse(purchaseRequest, lines);
    }

    @Override
    @Transactional
    @Auditable(action = "SubmitPurchaseRequest", resourceType = "PurchaseRequest")
    public PurchaseRequestResponse submitPurchaseRequest(@AuditResourceId UUID requestId, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = loadAndGuardStoreScope(requestId, actor);

        purchaseRequestTransitionHandler.validateTransition(purchaseRequest.getStatus(), PurchaseRequestStatus.SUBMITTED);

        purchaseRequest.setStatus(PurchaseRequestStatus.SUBMITTED);
        purchaseRequest.setSubmittedAt(LocalDateTime.now());
        purchaseRequest = saveAndFlush(purchaseRequest);

        procurementEventRecorder.recordPurchaseRequestSubmitted(purchaseRequest);
        return buildResponse(purchaseRequest, purchaseRequestLineRepository.findAllByPurchaseRequestId(requestId));
    }

    @Override
    @Transactional
    @Auditable(action = "ApprovePurchaseRequest", resourceType = "PurchaseRequest")
    public PurchaseRequestResponse approvePurchaseRequest(@AuditResourceId UUID requestId, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = loadAndGuardManagerScope(requestId, actor);
        assertNotSelfApproval(purchaseRequest, actor);

        purchaseRequestTransitionHandler.validateTransition(purchaseRequest.getStatus(), PurchaseRequestStatus.APPROVED);

        purchaseRequest.setStatus(PurchaseRequestStatus.APPROVED);
        purchaseRequest.setApprovedBy(actor.getUserId());
        purchaseRequest.setDecidedAt(LocalDateTime.now());
        purchaseRequest = saveAndFlush(purchaseRequest);

        procurementEventRecorder.recordPurchaseRequestApproved(purchaseRequest);
        return buildResponse(purchaseRequest, purchaseRequestLineRepository.findAllByPurchaseRequestId(requestId));
    }

    @Override
    @Transactional
    @Auditable(action = "RejectPurchaseRequest", resourceType = "PurchaseRequest")
    public PurchaseRequestResponse rejectPurchaseRequest(@AuditResourceId UUID requestId, RejectPurchaseRequestRequest request, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = loadAndGuardManagerScope(requestId, actor);
        assertNotSelfApproval(purchaseRequest, actor);

        purchaseRequestTransitionHandler.validateTransition(purchaseRequest.getStatus(), PurchaseRequestStatus.REJECTED);

        purchaseRequest.setStatus(PurchaseRequestStatus.REJECTED);
        purchaseRequest.setApprovedBy(actor.getUserId());
        purchaseRequest.setRejectionReason(request.reason());
        purchaseRequest.setDecidedAt(LocalDateTime.now());
        purchaseRequest = saveAndFlush(purchaseRequest);

        procurementEventRecorder.recordPurchaseRequestRejected(purchaseRequest);
        return buildResponse(purchaseRequest, purchaseRequestLineRepository.findAllByPurchaseRequestId(requestId));
    }

    @Override
    @Transactional
    @Auditable(action = "CancelPurchaseRequest", resourceType = "PurchaseRequest")
    public PurchaseRequestResponse cancelPurchaseRequest(@AuditResourceId UUID requestId, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = loadAndGuardStoreScope(requestId, actor);

        purchaseRequestTransitionHandler.validateTransition(purchaseRequest.getStatus(), PurchaseRequestStatus.CANCELLED);

        purchaseRequest.setCancelledAt(LocalDateTime.now());
        purchaseRequest.setStatus(PurchaseRequestStatus.CANCELLED);
        purchaseRequest = saveAndFlush(purchaseRequest);

        procurementEventRecorder.recordPurchaseRequestCancelled(purchaseRequest, actor.getUserId());
        return buildResponse(purchaseRequest, purchaseRequestLineRepository.findAllByPurchaseRequestId(requestId));
    }

    private PurchaseRequest loadAndGuardStoreScope(UUID requestId, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseRequest", requestId));
        UUID organizationId = storeService.getOrganizationIdForStore(purchaseRequest.getStoreId());
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, purchaseRequest.getStoreId());
        return purchaseRequest;
    }

    private PurchaseRequest loadAndGuardManagerScope(UUID requestId, UserPrincipal actor) {
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseRequest", requestId));
        UUID organizationId = storeService.getOrganizationIdForStore(purchaseRequest.getStoreId());
        RoleScopeGuard.assertCanManageStore(actor, organizationId, purchaseRequest.getStoreId());
        return purchaseRequest;
    }

    private void assertNotSelfApproval(PurchaseRequest purchaseRequest, UserPrincipal actor) {
        // RULE-13-02 — Maker-Checker: created_by không được trùng approved_by.
        if (Objects.equals(purchaseRequest.getCreatedBy(), actor.getUserId())) {
            throw new BusinessRuleViolationException("RULE-13-02",
                    "MAKER_CHECKER_VIOLATION: created_by không được trùng approved_by");
        }
    }

    private PurchaseRequest saveAndFlush(PurchaseRequest purchaseRequest) {
        try {
            return purchaseRequestRepository.saveAndFlush(purchaseRequest);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("PurchaseRequest", purchaseRequest.getId());
        }
    }

    private PurchaseRequestResponse buildResponse(PurchaseRequest purchaseRequest, List<PurchaseRequestLine> lines) {
        List<PurchaseRequestLineResponse> lineResponses = lines.stream().map(line -> {
            String sku = productService.getProductForCrossModule(line.getProductId()).sku();
            return new PurchaseRequestLineResponse(line.getProductId(), sku, line.getRequestedQuantity(),
                    line.getEstimatedUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    line.getRecommendedSupplierName());
        }).toList();
        return purchaseRequestMapper.toResponse(purchaseRequest, lineResponses);
    }

    private String generateNumber(String prefix) {
        String datePart = LocalDateTime.now().toLocalDate().toString().replace("-", "");
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "-" + datePart + "-" + randomPart;
    }
}
