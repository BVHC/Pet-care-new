package com.petcare.module.procurement.service;

import com.petcare.module.procurement.dto.CreatePurchaseRequestRequest;
import com.petcare.module.procurement.dto.PurchaseRequestResponse;
import com.petcare.module.procurement.dto.RejectPurchaseRequestRequest;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

/**
 * docs/api/procurement-v1.md C1 — CreatePurchaseRequest/SubmitPurchaseRequest/
 * Approve-RejectPurchaseRequest/CancelPurchaseRequest (RULE-13-01→03, FSM-12).
 */
public interface PurchaseRequestService {

    PurchaseRequestResponse createPurchaseRequest(UUID storeId, CreatePurchaseRequestRequest request, UserPrincipal actor);

    PurchaseRequestResponse submitPurchaseRequest(UUID requestId, UserPrincipal actor);

    PurchaseRequestResponse approvePurchaseRequest(UUID requestId, UserPrincipal actor);

    PurchaseRequestResponse rejectPurchaseRequest(UUID requestId, RejectPurchaseRequestRequest request, UserPrincipal actor);

    PurchaseRequestResponse cancelPurchaseRequest(UUID requestId, UserPrincipal actor);
}
