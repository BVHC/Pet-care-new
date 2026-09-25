package com.petcare.module.procurement.service;

import com.petcare.module.procurement.dto.CreatePurchaseOrderRequest;
import com.petcare.module.procurement.dto.PurchaseOrderResponse;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

/** docs/api/procurement-v1.md C2 — CreatePurchaseOrder/TrackPurchaseOrder (RULE-13-04). */
public interface PurchaseOrderService {

    PurchaseOrderResponse createPurchaseOrder(CreatePurchaseOrderRequest request, UserPrincipal actor);

    PurchaseOrderResponse getPurchaseOrder(UUID orderId, UserPrincipal actor);
}
