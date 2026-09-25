package com.petcare.module.order.service;

import com.petcare.module.order.dto.CreateOrderRequest;
import com.petcare.module.order.dto.OrderResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/order-v1.md C1-C3 — CreateOrder/CheckoutOrder/ViewOrder/CancelOrder (RULE-14-01/02/03/
 * 04/07/08, D-03). ConfirmOrder/ProcessOrder/PrepareProductOrder/CompleteStoreOrder/
 * CancelOrderWithRefund ngoài phạm vi (phụ thuộc Payment M16/Refund M17).
 */
public interface OrderService {

    OrderResponse createOrder(CreateOrderRequest request, UserPrincipal actor);

    OrderResponse checkoutOrder(UUID orderId, UserPrincipal actor);

    OrderResponse getOrder(UUID orderId, UserPrincipal actor);

    PageResponse<OrderResponse> listOrders(UserPrincipal actor, Pageable pageable);

    OrderResponse cancelOrder(UUID orderId, UserPrincipal actor);

    /**
     * System-triggered (ProcessOrderTimeoutJob), không qua actor guard. Idempotent: nếu order
     * không còn ở {@code PENDING_PAYMENT} (đã bị CancelOrder xử lý trước, hoặc không tồn tại) thì
     * no-op, không ném lỗi — an toàn khi job và CancelOrder race nhau.
     */
    void expireOrder(UUID orderId);
}
