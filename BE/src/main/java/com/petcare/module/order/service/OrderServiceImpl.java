package com.petcare.module.order.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.catalog.service.StorefrontService;
import com.petcare.module.inventory.service.InventoryItemService;
import com.petcare.module.order.dto.CreateOrderRequest;
import com.petcare.module.order.dto.OrderItemLineRequest;
import com.petcare.module.order.dto.OrderItemResponse;
import com.petcare.module.order.dto.OrderResponse;
import com.petcare.module.order.entity.Order;
import com.petcare.module.order.entity.OrderItem;
import com.petcare.module.order.fsm.OrderTransitionHandler;
import com.petcare.module.order.mapper.OrderMapper;
import com.petcare.module.order.repository.OrderItemRepository;
import com.petcare.module.order.repository.OrderRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.OrderChannel;
import com.petcare.platform.enums.OrderStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Module 14 — CreateOrder/CheckoutOrder/ViewOrder/CancelOrder (RULE-14-01/02/03/04/07/08, D-03) +
 * ConfirmOrder/ProcessOrder/PrepareProductOrder/CompleteStoreOrder (RULE-14-03/05/06).
 * D-03 split: Online -> PENDING_PAYMENT + reserveStock (RULE-14-04 giữ chỗ 15 phút) -> CONFIRMED
 * -> PROCESSING (commitReservation: reserve -> physical) -> READY -> DELIVERED; POS -> PAID +
 * deductPhysicalForOrder (trừ physical trực tiếp, không giữ chỗ) -> DELIVERED tức thời.
 * CheckoutOrder không đổi state (A2, docs/api/order-v1.md) — chỉ refresh {@code reservedUntil},
 * không qua FSM. {@code CancelOrderWithRefund} vẫn ngoài phạm vi — phụ thuộc Refund M17.
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final int HOLD_TTL_MINUTES = 15;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper;
    private final OrderTransitionHandler orderTransitionHandler;
    private final StoreService storeService;
    private final ProductService productService;
    private final StorefrontService storefrontService;
    private final InventoryItemService inventoryItemService;
    private final OrderEventRecorder orderEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "CreateOrder", resourceType = "Order")
    public OrderResponse createOrder(CreateOrderRequest request, UserPrincipal actor) {
        UUID organizationId = storeService.getOrganizationIdForStore(request.storeId());
        UUID customerId = resolveCustomerId(request, actor, organizationId);

        List<OrderLineData> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderItemLineRequest item : request.items()) {
            ProductResponse product = productService.getProductForCrossModule(item.productId());
            if (!Objects.equals(product.organizationId(), organizationId)) {
                throw new BusinessRuleViolationException("RULE-14-01", "Product không thuộc Organization của Store");
            }
            if (!product.isActive()) {
                throw new BusinessRuleViolationException("RULE-14-02", "Product không ACTIVE tại Store");
            }
            BigDecimal unitPrice = new BigDecimal(storefrontService.getEffectiveProductPrice(request.storeId(), item.productId()));
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            subtotal = subtotal.add(lineTotal);
            lines.add(new OrderLineData(item.productId(), item.quantity(), unitPrice, lineTotal, product.sku()));
        }

        boolean online = request.channel() == OrderChannel.ONLINE_APP;
        OrderStatus status = online ? OrderStatus.PENDING_PAYMENT : OrderStatus.PAID;
        Order order = new Order(request.storeId(), customerId, generateNumber("ORD"), request.channel(), status);
        order.setSubtotal(subtotal);
        order.setTotalAmount(subtotal);
        LocalDateTime reservedUntil = null;
        if (online) {
            reservedUntil = LocalDateTime.now().plusMinutes(HOLD_TTL_MINUTES);
            order.setReservedUntil(reservedUntil);
        }
        order = orderRepository.save(order);

        List<OrderItem> items = new ArrayList<>();
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        for (OrderLineData line : lines) {
            items.add(new OrderItem(order.getId(), line.productId(), line.quantity(), line.unitPrice(), line.lineTotal()));
            itemResponses.add(new OrderItemResponse(line.productId(), line.sku(), line.quantity(),
                    money(line.unitPrice()), money(line.lineTotal())));
        }
        orderItemRepository.saveAll(items);

        for (OrderLineData line : lines) {
            if (online) {
                inventoryItemService.reserveStock(request.storeId(), line.productId(), line.quantity(), order.getId(), reservedUntil);
            } else {
                inventoryItemService.deductPhysicalForOrder(request.storeId(), line.productId(), line.quantity());
            }
        }

        orderEventRecorder.recordOrderCreated(order);
        return orderMapper.toResponse(order, itemResponses);
    }

    @Override
    @Transactional
    @Auditable(action = "CheckoutOrder", resourceType = "Order")
    public OrderResponse checkoutOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        RoleScopeGuard.assertIsOrderOwner(actor, order.getCustomerId());
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidStateTransitionException("Order", order.getStatus().name(), OrderStatus.PENDING_PAYMENT.name());
        }

        // A2 (docs/api/order-v1.md) — CheckoutOrder chỉ refresh TTL 15 phút, không đổi state, không
        // qua FSM. Không đồng bộ inventory_reservations.expires_at — job đọc reserved_until từ
        // chính orders, cột đó bên Inventory không có code path đọc lại trong phạm vi task này.
        order.setReservedUntil(LocalDateTime.now().plusMinutes(HOLD_TTL_MINUTES));
        order = saveAndFlush(order);

        return buildResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        RoleScopeGuard.assertIsOrderOwner(actor, order.getCustomerId());
        return buildResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listOrders(UserPrincipal actor, Pageable pageable) {
        // RULE-14-01 — "Customer: đơn mình". Không có endpoint list-theo-Store/list-toàn-bộ trong
        // phạm vi task này (staff xem đơn để task sau, docs/api/order-v1.md B "TBD sau") nên không
        // mở rộng cho role khác CUSTOMER, kể cả SUPER_ADMIN.
        if (actor.getRole() != UserRole.CUSTOMER) {
            throw new AccessDeniedScopeException("CUSTOMER", actor.getRole().name());
        }
        Page<OrderResponse> page = orderRepository.findAllByCustomerId(actor.getUserId(), pageable)
                .map(this::buildResponse);
        return PageResponse.of(page);
    }

    @Override
    @Transactional
    @Auditable(action = "CancelOrder", resourceType = "Order")
    public OrderResponse cancelOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        if (actor.getRole() == UserRole.CUSTOMER) {
            RoleScopeGuard.assertIsOrderOwner(actor, order.getCustomerId());
        } else {
            UUID organizationId = storeService.getOrganizationIdForStore(order.getStoreId());
            RoleScopeGuard.assertCanOperateStoreOrder(actor, organizationId, order.getStoreId());
        }

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        order = saveAndFlush(order);

        inventoryItemService.releaseReservation(order.getId());
        orderEventRecorder.recordOrderCancelled(order);

        return buildResponse(order);
    }

    @Override
    @Transactional
    @Auditable(action = "ConfirmOrder", resourceType = "Order")
    public OrderResponse confirmOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        UUID organizationId = storeService.getOrganizationIdForStore(order.getStoreId());
        RoleScopeGuard.assertCanOperateStoreOrder(actor, organizationId, order.getStoreId());

        // RULE-14-03 — ConfirmOrder là nhánh rẽ Online staged fulfillment; POS không bao giờ rời
        // khỏi đường instant PAID->DELIVERED.
        if (order.getChannel() != OrderChannel.ONLINE_APP) {
            throw new BusinessRuleViolationException("RULE-14-03", "ConfirmOrder chỉ áp dụng cho đơn Online");
        }

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);
        order.setStatus(OrderStatus.CONFIRMED);
        order = saveAndFlush(order);

        orderEventRecorder.recordOrderConfirmed(order);
        return buildResponse(order);
    }

    @Override
    @Transactional
    @Auditable(action = "ProcessOrder", resourceType = "Order")
    public OrderResponse processOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        UUID organizationId = storeService.getOrganizationIdForStore(order.getStoreId());
        RoleScopeGuard.assertCanProcessStoreOrder(actor, organizationId, order.getStoreId());

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.PROCESSING);
        order.setStatus(OrderStatus.PROCESSING);
        order = saveAndFlush(order);

        inventoryItemService.commitReservation(order.getId()); // RULE-14-05 invariant: reserve -> physical
        orderEventRecorder.recordOrderProcessed(order);
        return buildResponse(order);
    }

    @Override
    @Transactional
    @Auditable(action = "PrepareProductOrder", resourceType = "Order")
    public OrderResponse prepareProductOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        UUID organizationId = storeService.getOrganizationIdForStore(order.getStoreId());
        RoleScopeGuard.assertCanOperateStoreInventory(actor, organizationId, order.getStoreId());

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.READY);
        order.setStatus(OrderStatus.READY);
        order = saveAndFlush(order);
        // Không đụng inventory — physical đã trừ chính thức ở ProcessOrder; đây chỉ là soạn/đóng gói.

        orderEventRecorder.recordProductOrderPrepared(order);
        return buildResponse(order);
    }

    @Override
    @Transactional
    @Auditable(action = "CompleteStoreOrder", resourceType = "Order")
    public OrderResponse completeStoreOrder(@AuditResourceId UUID orderId, UserPrincipal actor) {
        Order order = loadOrder(orderId);
        UUID organizationId = storeService.getOrganizationIdForStore(order.getStoreId());
        RoleScopeGuard.assertCanOperateStoreOrder(actor, organizationId, order.getStoreId());

        // RULE-14-03/06 — PAID->DELIVERED chỉ dành cho POS instant handover; đơn Online đang PAID
        // phải đi qua staged confirm/process/prepare, không được nhảy thẳng sang complete.
        if (order.getStatus() == OrderStatus.PAID && order.getChannel() != OrderChannel.POS_RETAIL) {
            throw new BusinessRuleViolationException("RULE-14-03", "CompleteStoreOrder từ PAID chỉ dành cho đơn POS");
        }

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.DELIVERED);
        order.setStatus(OrderStatus.DELIVERED);
        order = saveAndFlush(order);

        // Pickup code (RULE-14-06 nhánh Online) cố ý bỏ qua theo phạm vi đã chốt — không đọc
        // request body, không lưu/tra mã. docs/api/order-v1.md Q6 vẫn TBD.
        orderEventRecorder.recordOrderDelivered(order);
        return buildResponse(order);
    }

    @Override
    @Transactional
    public void expireOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            // Đã bị CancelOrder xử lý trước (race với ProcessOrderTimeoutJob) hoặc không tồn tại
            // — idempotent no-op, không ném lỗi giữa batch job.
            return;
        }

        orderTransitionHandler.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        order = saveAndFlush(order);

        inventoryItemService.releaseReservation(order.getId());
        orderEventRecorder.recordOrderTimedOut(order);
    }

    private UUID resolveCustomerId(CreateOrderRequest request, UserPrincipal actor, UUID organizationId) {
        if (request.channel() == OrderChannel.ONLINE_APP) {
            // Online — actor phải chính là Customer, customerId luôn lấy từ JWT (actor.getUserId()),
            // không nhận field client gửi để chống mạo danh khách khác.
            if (actor.getRole() != UserRole.CUSTOMER) {
                throw new AccessDeniedScopeException("CUSTOMER", actor.getRole().name());
            }
            return actor.getUserId();
        }
        // POS_RETAIL — Receptionist/Manager/Admin đại diện khách tại quầy tạo đơn.
        RoleScopeGuard.assertCanOperateStoreOrder(actor, organizationId, request.storeId());
        if (request.customerId() == null) {
            throw new BusinessRuleViolationException("RULE-14-01", "customerId bắt buộc cho đơn POS_RETAIL");
        }
        return request.customerId();
    }

    private Order loadOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    private Order saveAndFlush(Order order) {
        try {
            return orderRepository.saveAndFlush(order);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Order", order.getId());
        }
    }

    private OrderResponse buildResponse(Order order) {
        List<OrderItemResponse> items = orderItemRepository.findAllByOrderId(order.getId()).stream()
                .map(item -> {
                    String sku = productService.getProductForCrossModule(item.getProductId()).sku();
                    return new OrderItemResponse(item.getProductId(), sku, item.getQuantity(),
                            money(item.getUnitPrice()), money(item.getLineTotal()));
                }).toList();
        return orderMapper.toResponse(order, items);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String generateNumber(String prefix) {
        String datePart = LocalDateTime.now().toLocalDate().toString().replace("-", "");
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "-" + datePart + "-" + randomPart;
    }

    private record OrderLineData(UUID productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal, String sku) {
    }
}
