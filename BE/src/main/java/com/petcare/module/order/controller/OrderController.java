package com.petcare.module.order.controller;

import com.petcare.module.order.dto.CreateOrderRequest;
import com.petcare.module.order.dto.OrderResponse;
import com.petcare.module.order.service.OrderService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 14 — docs/api/order-v1.md C1-C4 (CreateOrder/CheckoutOrder/ViewOrder/CancelOrder,
 * RULE-14-01→04/07/08, D-03) + ConfirmOrder/ProcessOrder/PrepareProductOrder/CompleteStoreOrder
 * (RULE-14-03/05/06). CreateOrder mở cho CUSTOMER (Online) lẫn RECEPTIONIST/STORE_MANAGER/
 * ORGANIZATION_ADMIN/SUPER_ADMIN (POS) — Service tự phân nhánh guard theo channel
 * ({@code resolveCustomerId}). Checkout/View chỉ CUSTOMER (+ SUPER_ADMIN bypass ở View). Cancel mở
 * cho cả 2 phía (Customer chủ đơn hoặc staff tại Store). Confirm/Complete: Receptionist (+
 * Store/Org/SuperAdmin). Process: Receptionist hoặc InventoryStaff (RULE-14-05 nêu đích danh cả
 * 2). Prepare: InventoryStaff.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class OrderController {

    private final OrderService orderService;

    @PreAuthorize("hasRole('CUSTOMER') or hasRole('RECEPTIONIST') or hasRole('STORE_MANAGER') "
            + "or hasRole('ORGANIZATION_ADMIN') or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/api/orders/{id}/checkout")
    public ResponseEntity<ApiResponse<OrderResponse>> checkoutOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.checkoutOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('CUSTOMER') or hasRole('SUPER_ADMIN')")
    @GetMapping("/api/orders")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> listOrders(
            @AuthenticationPrincipal UserPrincipal actor, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.listOrders(actor, pageable)));
    }

    @PreAuthorize("hasRole('CUSTOMER') or hasRole('SUPER_ADMIN')")
    @GetMapping("/api/orders/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('CUSTOMER') or hasRole('RECEPTIONIST') or hasRole('STORE_MANAGER') "
            + "or hasRole('ORGANIZATION_ADMIN') or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.cancelOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('RECEPTIONIST') or hasRole('STORE_MANAGER') or hasRole('ORGANIZATION_ADMIN') "
            + "or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders/{id}/confirm")
    public ResponseEntity<ApiResponse<OrderResponse>> confirmOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.confirmOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('RECEPTIONIST') or hasRole('INVENTORY_STAFF') or hasRole('STORE_MANAGER') "
            + "or hasRole('ORGANIZATION_ADMIN') or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders/{id}/process")
    public ResponseEntity<ApiResponse<OrderResponse>> processOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.processOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('INVENTORY_STAFF') or hasRole('STORE_MANAGER') or hasRole('ORGANIZATION_ADMIN') "
            + "or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders/{id}/prepare")
    public ResponseEntity<ApiResponse<OrderResponse>> prepareProductOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.prepareProductOrder(orderId, actor)));
    }

    @PreAuthorize("hasRole('RECEPTIONIST') or hasRole('STORE_MANAGER') or hasRole('ORGANIZATION_ADMIN') "
            + "or hasRole('SUPER_ADMIN')")
    @PostMapping("/api/orders/{id}/complete")
    public ResponseEntity<ApiResponse<OrderResponse>> completeStoreOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.completeStoreOrder(orderId, actor)));
    }
}
