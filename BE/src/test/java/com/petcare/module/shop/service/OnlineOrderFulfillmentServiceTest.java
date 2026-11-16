package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineOrderLineInput;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.ShipRequest;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.mapper.OnlineOrderMapperImpl;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import org.springframework.data.domain.Page;

/**
 * Xử lý và giao đơn online (UC65, docs/adr/0146): thứ tự {@code đọc chi nhánh → kiểm phạm vi → khóa online_orders →
 * nạp → trạng thái → đầu vào → ghi → thông báo}, 404 / 403 trước mọi khóa, mọi từ-trạng-thái sai là 409 và nhánh lỗi
 * không ghi gì.
 */
class OnlineOrderFulfillmentServiceTest {

    private static final long ONLINE_ID = 600L;
    private static final long ORDER_ID = 500L;
    private static final long BRANCH = 3L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-15T03:00:00.123456Z");

    private final OnlineOrderRepository onlineOrders = mock(OnlineOrderRepository.class);
    private final OnlineSalesOrderApi sales = mock(OnlineSalesOrderApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final OnlineOrderNotices notices = mock(OnlineOrderNotices.class);
    private final BranchScope scope = mock(BranchScope.class);
    private OnlineOrderFulfillmentService service;

    @BeforeEach
    void setUp() {
        when(onlineOrders.findBranchIdById(ONLINE_ID)).thenReturn(Optional.of(BRANCH));
        when(onlineOrders.lockById(ONLINE_ID)).thenReturn(Optional.of(ONLINE_ID));
        when(onlineOrders.saveAndFlush(any(OnlineOrder.class))).thenAnswer(call -> call.getArgument(0));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh 1", "Địa chỉ", "0280000000", BranchQueryApi.BranchStatus.ACTIVE, true)));
        when(sales.findLines(anyCollection())).thenReturn(
                Map.of(ORDER_ID, List.of(new OnlineOrderLineInput(12L, "Hạt cho chó", 2, 100_000))));
        when(catalog.findProductSaleDisplay(anyCollection())).thenReturn(Map.of(12L, new ProductSaleDisplay("túi", null)));
        when(config.getInt(ConfigKey.SHOP_MAX_REDELIVERY)).thenReturn(2);
        service = new OnlineOrderFulfillmentService(onlineOrders, sales, branches, catalog, config, notices,
                new OnlineOrderTransitionHandler(), new OnlineOrderMapperImpl(), scope,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OnlineOrder order(OnlineOrderStatus status) {
        OnlineOrder order = OnlineOrder.place("DO-2611-00001", ORDER_ID, 9L, BRANCH, "An", "0901", "12 Lê Lợi", 30_000,
                NOW);
        ReflectionTestUtils.setField(order, "id", ONLINE_ID);
        ReflectionTestUtils.setField(order, "status", status);
        when(onlineOrders.findById(ONLINE_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static ShipRequest ship() {
        return new ShipRequest("  Giao hàng nhanh ", " GHN1 ");
    }

    // ================================================================ thứ tự khóa và ghi

    @Test
    void everyTransitionReadsTheBranchChecksScopeLocksThenLoadsThenWritesThenNotifies() {
        OnlineOrder order = order(OnlineOrderStatus.PROCESSING);

        OnlineOrderResponse response = service.ship(ONLINE_ID, ship());

        InOrder inOrder = inOrder(onlineOrders, scope, notices);
        inOrder.verify(onlineOrders).findBranchIdById(ONLINE_ID);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(onlineOrders).lockById(ONLINE_ID);
        inOrder.verify(onlineOrders).findById(ONLINE_ID);
        inOrder.verify(onlineOrders).saveAndFlush(order);
        inOrder.verify(notices).shipped(order);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.SHIPPED);
        assertThat(order.getCarrier()).isEqualTo("Giao hàng nhanh");
        assertThat(order.getTrackingCode()).isEqualTo("GHN1");
        assertThat(order.getShippedAt()).as("giờ từ Clock, cắt về micro giây").isEqualTo(NOW_MICROS);
        assertThat(response.status()).isEqualTo(OnlineOrderStatus.SHIPPED);
        assertThat(response.carrier()).isEqualTo("Giao hàng nhanh");
        assertThat(response.total()).isEqualTo(230_000);
    }

    @Test
    void startProcessingMovesPaidToProcessingWithoutNotifying() {
        OnlineOrder order = order(OnlineOrderStatus.PAID);

        service.startProcessing(ONLINE_ID);

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.PROCESSING);
        verifyNoInteractions(notices);
    }

    @Test
    void markDeliveredSetsDeliveredAtFromTheClockWithoutNotifying() {
        OnlineOrder order = order(OnlineOrderStatus.SHIPPED);

        service.markDelivered(ONLINE_ID);

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isEqualTo(NOW_MICROS);
        verifyNoInteractions(notices);
    }

    @Test
    void failDeliveryStoresTheTrimmedReasonAndNotifiesWithIt() {
        OnlineOrder order = order(OnlineOrderStatus.SHIPPED);

        service.failDelivery(ONLINE_ID, "  Khách không nghe máy ");

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.DELIVERY_FAILED);
        assertThat(order.getDeliveryFailureReason()).isEqualTo("Khách không nghe máy");
        verify(notices).deliveryFailed(order, "Khách không nghe máy");
    }

    @Test
    void redeliverAddsOneAttemptReplacesCarrierAndTrackingAndResetsShippedAtWithoutNotifying() {
        OnlineOrder order = order(OnlineOrderStatus.DELIVERY_FAILED);
        ReflectionTestUtils.setField(order, "redeliveryCount", (short) 1);
        ReflectionTestUtils.setField(order, "shippedAt", Instant.parse("2026-11-10T00:00:00Z"));

        service.redeliver(ONLINE_ID, new ShipRequest("J&T", "JT9"));

        InOrder inOrder = inOrder(onlineOrders, config);
        inOrder.verify(onlineOrders).lockById(ONLINE_ID);
        inOrder.verify(config).getInt(ConfigKey.SHOP_MAX_REDELIVERY);
        inOrder.verify(onlineOrders).saveAndFlush(order);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.SHIPPED);
        assertThat(order.getRedeliveryCount()).isEqualTo((short) 2);
        assertThat(order.getCarrier()).isEqualTo("J&T");
        assertThat(order.getTrackingCode()).isEqualTo("JT9");
        assertThat(order.getShippedAt()).isEqualTo(NOW_MICROS);
        verifyNoInteractions(notices);
    }

    // ================================================================ lỗi không ghi gì

    @Test
    void redeliverAtTheConfiguredMaximumIs400BrBh11AndWritesNothing() {
        OnlineOrder order = order(OnlineOrderStatus.DELIVERY_FAILED);
        ReflectionTestUtils.setField(order, "redeliveryCount", (short) 2);

        assertThatThrownBy(() -> service.redeliver(ONLINE_ID, ship()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-BH-11"));

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.DELIVERY_FAILED);
        assertThat(order.getRedeliveryCount()).isEqualTo((short) 2);
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void theRedeliveryLimitIsReadFromTheConfigAtRunTime() {
        OnlineOrder order = order(OnlineOrderStatus.DELIVERY_FAILED);
        ReflectionTestUtils.setField(order, "redeliveryCount", (short) 2);
        when(config.getInt(ConfigKey.SHOP_MAX_REDELIVERY)).thenReturn(3);

        service.redeliver(ONLINE_ID, ship());

        assertThat(order.getRedeliveryCount()).isEqualTo((short) 3);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.SHIPPED);
    }

    @Test
    void redeliverAtMaximumZeroIsAlwaysRefused() {
        order(OnlineOrderStatus.DELIVERY_FAILED);
        when(config.getInt(ConfigKey.SHOP_MAX_REDELIVERY)).thenReturn(0);

        assertThatThrownBy(() -> service.redeliver(ONLINE_ID, ship())).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void shipAndRedeliverNeedBothCarrierAndTrackingCodeOtherwise400BrBh11() {
        OnlineOrder processing = order(OnlineOrderStatus.PROCESSING);
        for (ShipRequest bad : List.of(new ShipRequest(null, "T"), new ShipRequest("  ", "T"), new ShipRequest("C", null),
                new ShipRequest("C", " "))) {
            assertThatThrownBy(() -> service.ship(ONLINE_ID, bad)).isInstanceOfSatisfying(
                    BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-BH-11"));
        }
        assertThat(processing.getStatus()).isEqualTo(OnlineOrderStatus.PROCESSING);
        order(OnlineOrderStatus.DELIVERY_FAILED);
        for (ShipRequest bad : List.of(new ShipRequest(null, "T"), new ShipRequest("C", " "))) {
            assertThatThrownBy(() -> service.redeliver(ONLINE_ID, bad)).isInstanceOfSatisfying(
                    BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-BH-11"));
        }
        verify(onlineOrders, never()).saveAndFlush(any());
        verifyNoInteractions(notices);
    }

    @Test
    void failDeliveryWithABlankReasonIs400BrBh11AndWritesNothing() {
        OnlineOrder order = order(OnlineOrderStatus.SHIPPED);

        assertThatThrownBy(() -> service.failDelivery(ONLINE_ID, "  ")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.failDelivery(ONLINE_ID, null)).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.SHIPPED);
        assertThat(order.getDeliveryFailureReason()).isNull();
        verify(onlineOrders, never()).saveAndFlush(any());
        verifyNoInteractions(notices);
    }

    @Test
    void everyWrongFromStateIs409ForEveryTransitionAndWritesNothing() {
        Map<String, OnlineOrderStatus> only = Map.of("start", OnlineOrderStatus.PAID, "ship", OnlineOrderStatus.PROCESSING,
                "delivered", OnlineOrderStatus.SHIPPED, "fail", OnlineOrderStatus.SHIPPED, "redeliver",
                OnlineOrderStatus.DELIVERY_FAILED);
        for (OnlineOrderStatus status : OnlineOrderStatus.values()) {
            order(status);
            for (Map.Entry<String, OnlineOrderStatus> entry : only.entrySet()) {
                if (entry.getValue() == status) {
                    continue;
                }
                assertThatThrownBy(() -> run(entry.getKey())).as("%s từ %s", entry.getKey(), status)
                        .isInstanceOf(InvalidStateTransitionException.class);
            }
        }
        verify(onlineOrders, never()).saveAndFlush(any());
        verifyNoInteractions(notices);
        verify(config, never()).getInt(any());
    }

    private void run(String transition) {
        switch (transition) {
            case "start" -> service.startProcessing(ONLINE_ID);
            case "ship" -> service.ship(ONLINE_ID, ship());
            case "delivered" -> service.markDelivered(ONLINE_ID);
            case "fail" -> service.failDelivery(ONLINE_ID, "Lý do");
            default -> service.redeliver(ONLINE_ID, ship());
        }
    }

    @Test
    void anOrderOfAnotherBranchIs403BeforeAnyLockOrLoad() {
        doThrow(new AccessDeniedScopeException("branch:" + BRANCH, "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.startProcessing(ONLINE_ID)).isInstanceOf(AccessDeniedScopeException.class);

        verify(onlineOrders, never()).lockById(any());
        verify(onlineOrders, never()).findById(any());
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void aMissingOrderIs404BeforeAnyScopeCheckOrLock() {
        when(onlineOrders.findBranchIdById(ONLINE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markDelivered(ONLINE_ID)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(scope);
        verify(onlineOrders, never()).lockById(any());
    }

    @Test
    void anOrderThatVanishedBeforeTheLockIs404() {
        when(onlineOrders.lockById(ONLINE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startProcessing(ONLINE_ID)).isInstanceOf(ResourceNotFoundException.class);

        verify(onlineOrders, never()).findById(any());
    }

    @Test
    void aFailingNotificationPropagatesSoTheTransactionRollsBack() {
        order(OnlineOrderStatus.SHIPPED);
        doThrow(new IllegalStateException("outbox")).when(notices).deliveryFailed(any(), eq("Lý do"));

        assertThatThrownBy(() -> service.failDelivery(ONLINE_ID, "Lý do")).isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ xem

    @Test
    void getChecksTheScopeOfTheLoadedOrder() {
        order(OnlineOrderStatus.PAID);
        doThrow(new AccessDeniedScopeException("branch:" + BRANCH, "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.get(ONLINE_ID)).isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(sales);
    }

    @Test
    void listWithoutStatusDefaultsToTheToDoSetOfTheCallersBranchOldestFirst() {
        when(scope.resolve(null)).thenReturn(BRANCH);
        OnlineOrder order = order(OnlineOrderStatus.PAID);
        when(onlineOrders.findByBranchIdAndStatusIn(eq(BRANCH), anyCollection(), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(order), call.getArgument(2), 1));

        var page = service.list(null, 0, 500);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<OnlineOrderStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(onlineOrders).findByBranchIdAndStatusIn(eq(BRANCH), statuses.capture(), pageable.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(OnlineOrderStatus.PAID, OnlineOrderStatus.PROCESSING,
                OnlineOrderStatus.DELIVERY_FAILED);
        assertThat(pageable.getValue().getPageSize()).as("size kẹp ở 100").isEqualTo(100);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("createdAt: ASC,id: ASC");
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).total()).isEqualTo(230_000);
        assertThat(page.content().get(0).itemCount()).isEqualTo(1);
    }

    @Test
    void listWithAStatusFiltersOnlyThatStatus() {
        when(scope.resolve(null)).thenReturn(BRANCH);
        when(onlineOrders.findByBranchIdAndStatusIn(eq(BRANCH), anyCollection(), any(Pageable.class)))
                .thenAnswer(call -> Page.empty(call.<Pageable>getArgument(2)));

        service.list(OnlineOrderStatus.SHIPPED, -3, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<OnlineOrderStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(onlineOrders).findByBranchIdAndStatusIn(eq(BRANCH), statuses.capture(), pageable.capture());
        assertThat(statuses.getValue()).isEqualTo(EnumSet.of(OnlineOrderStatus.SHIPPED));
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }
}
