package com.petcare.module.order.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.catalog.service.StorefrontService;
import com.petcare.module.inventory.service.InventoryItemService;
import com.petcare.module.order.dto.CreateOrderRequest;
import com.petcare.module.order.dto.OrderItemLineRequest;
import com.petcare.module.order.entity.Order;
import com.petcare.module.order.entity.OrderItem;
import com.petcare.module.order.fsm.OrderTransitionHandler;
import com.petcare.module.order.mapper.OrderMapper;
import com.petcare.module.order.mapper.OrderMapperImpl;
import com.petcare.module.order.repository.OrderItemRepository;
import com.petcare.module.order.repository.OrderRepository;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.enums.OrderChannel;
import com.petcare.platform.enums.OrderStatus;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-14-01→08 (D-03). */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private StoreService storeService;
    @Mock
    private ProductService productService;
    @Mock
    private StorefrontService storefrontService;
    @Mock
    private InventoryItemService inventoryItemService;
    @Mock
    private OrderEventRecorder orderEventRecorder;

    private final OrderMapper orderMapper = new OrderMapperImpl();
    private final OrderTransitionHandler transitionHandler = new OrderTransitionHandler();

    private OrderServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static ProductResponse product(UUID productId, UUID organizationId, String sku, boolean active) {
        return new ProductResponse(productId, organizationId, sku, null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, "10000.00", "8000.00", active);
    }

    private static Order order(UUID storeId, UUID customerId, OrderChannel channel, OrderStatus status) {
        Order order = new Order(storeId, customerId, "ORD-20260101-abcd1234", channel, status);
        order.setId(UUID.randomUUID());
        return order;
    }

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, orderItemRepository, orderMapper, transitionHandler,
                storeService, productService, storefrontService, inventoryItemService, orderEventRecorder);
    }

    @Test
    void createOrder_online_reservesStock_emitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01", true));
        when(storefrontService.getEffectiveProductPrice(storeId, productId)).thenReturn("10000.00");
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var request = new CreateOrderRequest(storeId, OrderChannel.ONLINE_APP, null,
                List.of(new OrderItemLineRequest(productId, 2)));
        var response = service.createOrder(request, customer);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(response.customerId()).isEqualTo(customer.getUserId());
        assertThat(response.subtotal()).isEqualTo("20000.00");
        assertThat(response.reservedUntil()).isNotNull();
        verify(inventoryItemService).reserveStock(eq(storeId), eq(productId), eq(2), any(UUID.class), any(LocalDateTime.class));
        verify(orderEventRecorder).recordOrderCreated(any(Order.class));
    }

    @Test
    void createOrder_pos_deductsPhysicalDirectly_noReservation() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UserPrincipal receptionist = principal(UserRole.RECEPTIONIST, organizationId, storeId);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01", true));
        when(storefrontService.getEffectiveProductPrice(storeId, productId)).thenReturn("15000.00");
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        var request = new CreateOrderRequest(storeId, OrderChannel.POS_RETAIL, customerId,
                List.of(new OrderItemLineRequest(productId, 1)));
        var response = service.createOrder(request, receptionist);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.reservedUntil()).isNull();
        verify(inventoryItemService).deductPhysicalForOrder(storeId, productId, 1);
        verify(inventoryItemService, never()).reserveStock(any(), any(), anyInt(), any(), any());
    }

    @Test
    void createOrder_productFromOtherOrg_throwsBusinessRuleViolation_RULE_14_01() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, otherOrganizationId, "SKU01", true));

        var request = new CreateOrderRequest(storeId, OrderChannel.ONLINE_APP, null,
                List.of(new OrderItemLineRequest(productId, 1)));

        assertThatThrownBy(() -> service.createOrder(request, principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-01"));
    }

    @Test
    void createOrder_productInactive_throwsBusinessRuleViolation_RULE_14_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01", false));

        var request = new CreateOrderRequest(storeId, OrderChannel.ONLINE_APP, null,
                List.of(new OrderItemLineRequest(productId, 1)));

        assertThatThrownBy(() -> service.createOrder(request, principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-02"));
    }

    @Test
    void createOrder_onlineActorNotCustomer_throwsAccessDeniedScope() {
        UUID storeId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(UUID.randomUUID());

        var request = new CreateOrderRequest(storeId, OrderChannel.ONLINE_APP, null,
                List.of(new OrderItemLineRequest(UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.createOrder(request, principal(UserRole.RECEPTIONIST, UUID.randomUUID(), storeId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void createOrder_posMissingCustomerId_throwsBusinessRuleViolation_RULE_14_01() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        var request = new CreateOrderRequest(storeId, OrderChannel.POS_RETAIL, null,
                List.of(new OrderItemLineRequest(UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.createOrder(request, principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-01"));
    }

    @Test
    void createOrder_posWrongStoreScope_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        var request = new CreateOrderRequest(storeId, OrderChannel.POS_RETAIL, UUID.randomUUID(),
                List.of(new OrderItemLineRequest(UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.createOrder(request, principal(UserRole.RECEPTIONIST, organizationId, otherStoreId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void checkoutOrder_pendingPayment_refreshesTtl() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        Order order = order(storeId, customer.getUserId(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        order.setReservedUntil(LocalDateTime.now().plusMinutes(1));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var before = order.getReservedUntil();
        var response = service.checkoutOrder(order.getId(), customer);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(response.reservedUntil()).isAfter(before);
    }

    @Test
    void checkoutOrder_notOwner_throwsAccessDeniedScope() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.checkoutOrder(order.getId(), principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void checkoutOrder_wrongState_throwsInvalidStateTransition() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        Order order = order(storeId, customer.getUserId(), OrderChannel.ONLINE_APP, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.checkoutOrder(order.getId(), customer))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void getOrder_owner_returnsOrder() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        Order order = order(storeId, customer.getUserId(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.getOrder(order.getId(), customer);

        assertThat(response.orderId()).isEqualTo(order.getId());
    }

    @Test
    void getOrder_notOwner_throwsAccessDeniedScope() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getOrder(order.getId(), principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listOrders_nonCustomerActor_throwsAccessDeniedScope() {
        assertThatThrownBy(() -> service.listOrders(principal(UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID()),
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void cancelOrder_ownerFromPendingPayment_releasesReservationAndCancels() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        Order order = order(storeId, customer.getUserId(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.cancelOrder(order.getId(), customer);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryItemService).releaseReservation(order.getId());
        verify(orderEventRecorder).recordOrderCancelled(any(Order.class));
    }

    @Test
    void cancelOrder_receptionistAtStore_cancelsOwnedByOtherCustomer() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.cancelOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_fromPaid_throwsInvalidStateTransition() {
        UUID storeId = UUID.randomUUID();
        UserPrincipal customer = principal(UserRole.CUSTOMER, null, null);
        Order order = order(storeId, customer.getUserId(), OrderChannel.POS_RETAIL, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(order.getId(), customer))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(inventoryItemService, never()).releaseReservation(any());
    }

    @Test
    void confirmOrder_receptionistFromPaidOnline_transitionsToConfirmed_emitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.confirmOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderEventRecorder).recordOrderConfirmed(any(Order.class));
    }

    @Test
    void confirmOrder_posChannel_throwsBusinessRuleViolation_RULE_14_03() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.POS_RETAIL, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.confirmOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-03"));
    }

    @Test
    void confirmOrder_wrongRole_throwsAccessDeniedScope() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.confirmOrder(order.getId(), principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void confirmOrder_wrongState_throwsInvalidStateTransition() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.CONFIRMED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.confirmOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void processOrder_receptionistOrInventoryStaff_transitionsToProcessing_commitsReservation() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.CONFIRMED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.processOrder(order.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.PROCESSING);
        verify(inventoryItemService).commitReservation(order.getId());
        verify(orderEventRecorder).recordOrderProcessed(any(Order.class));
    }

    @Test
    void processOrder_customerActor_throwsAccessDeniedScope() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.CONFIRMED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.processOrder(order.getId(), principal(UserRole.CUSTOMER, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(inventoryItemService, never()).commitReservation(any());
    }

    @Test
    void processOrder_wrongState_throwsInvalidStateTransition() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.processOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(inventoryItemService, never()).commitReservation(any());
    }

    @Test
    void prepareProductOrder_inventoryStaff_transitionsToReady_emitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PROCESSING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.prepareProductOrder(order.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.READY);
        verify(inventoryItemService, never()).commitReservation(any());
        verify(inventoryItemService, never()).deductPhysicalForOrder(any(), any(), anyInt());
        verify(orderEventRecorder).recordProductOrderPrepared(any(Order.class));
    }

    @Test
    void prepareProductOrder_receptionistActor_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PROCESSING);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.prepareProductOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void completeStoreOrder_posFromPaid_transitionsToDelivered_emitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.POS_RETAIL, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.completeStoreOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(orderEventRecorder).recordOrderDelivered(any(Order.class));
    }

    @Test
    void completeStoreOrder_onlineFromReady_transitionsToDelivered_emitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.READY);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderItemRepository.findAllByOrderId(order.getId())).thenReturn(List.of());

        var response = service.completeStoreOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId));

        assertThat(response.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(orderEventRecorder).recordOrderDelivered(any(Order.class));
    }

    @Test
    void completeStoreOrder_onlineFromPaid_throwsBusinessRuleViolation_RULE_14_03() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.completeStoreOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-14-03"));
    }

    @Test
    void completeStoreOrder_wrongState_throwsInvalidStateTransition() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.CONFIRMED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.completeStoreOrder(order.getId(), principal(UserRole.RECEPTIONIST, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void expireOrder_pendingPayment_cancelsAndReleasesReservation() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.expireOrder(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryItemService).releaseReservation(order.getId());
        verify(orderEventRecorder).recordOrderTimedOut(any(Order.class));
    }

    @Test
    void expireOrder_alreadyCancelledByRace_noOp() {
        UUID storeId = UUID.randomUUID();
        Order order = order(storeId, UUID.randomUUID(), OrderChannel.ONLINE_APP, OrderStatus.CANCELLED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        service.expireOrder(order.getId());

        verify(inventoryItemService, never()).releaseReservation(any());
        verify(orderEventRecorder, never()).recordOrderTimedOut(any());
    }

    @Test
    void expireOrder_notFound_noOp() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        service.expireOrder(orderId);

        verify(inventoryItemService, never()).releaseReservation(any());
    }
}
