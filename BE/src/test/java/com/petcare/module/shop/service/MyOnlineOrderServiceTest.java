package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderRef;
import com.petcare.module.payment.api.PaymentTransactionApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineCancelType;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineOrderLineInput;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.mapper.OnlineOrderMapperImpl;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Đơn online của tôi (UC64, docs/adr/0145): thứ tự khóa của hủy đơn ({@code online_orders} → {@code orders} →
 * {@code payment_transactions} → {@code stock_reservations}), 404 trước mọi khóa, và các nhánh lỗi không ghi gì.
 */
class MyOnlineOrderServiceTest {

    private static final long ACCOUNT = 5L;
    private static final long CUSTOMER = 9L;
    private static final long ONLINE_ID = 600L;
    private static final long ORDER_ID = 500L;
    private static final long BRANCH = 3L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");

    private final OnlineOrderRepository onlineOrders = mock(OnlineOrderRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final OnlineSalesOrderApi sales = mock(OnlineSalesOrderApi.class);
    private final StockApi stock = mock(StockApi.class);
    private final PaymentTransactionApi payments = mock(PaymentTransactionApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private MyOnlineOrderService service;

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(onlineOrders.findCustomerIdById(ONLINE_ID)).thenReturn(Optional.of(CUSTOMER));
        when(onlineOrders.lockById(ONLINE_ID)).thenReturn(Optional.of(ONLINE_ID));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh 1", "Địa chỉ", "0280000000", BranchQueryApi.BranchStatus.ACTIVE, true)));
        when(sales.findLines(anyCollection())).thenReturn(
                Map.of(ORDER_ID, List.of(new OnlineOrderLineInput(12L, "Hạt cho chó", 2, 100_000))));
        when(catalog.findProductSaleDisplay(anyCollection())).thenReturn(Map.of(12L, new ProductSaleDisplay("túi", null)));
        service = new MyOnlineOrderService(onlineOrders, customers, sales, stock, payments, branches, catalog,
                new OnlineOrderTransitionHandler(), new OnlineOrderMapperImpl(), scope,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OnlineOrder order(OnlineOrderStatus status) {
        OnlineOrder order = OnlineOrder.place("DO-2611-00001", ORDER_ID, CUSTOMER, BRANCH, "An", "0901", "12 Lê Lợi",
                30_000, NOW);
        ReflectionTestUtils.setField(order, "id", ONLINE_ID);
        ReflectionTestUtils.setField(order, "status", status);
        when(onlineOrders.findById(ONLINE_ID)).thenReturn(Optional.of(order));
        when(onlineOrders.saveAndFlush(any(OnlineOrder.class))).thenAnswer(call -> call.getArgument(0));
        return order;
    }

    // ================================================================ hủy

    @Test
    void cancelLocksTheOnlineOrderFirstThenOrderThenTransactionsThenReservations() {
        OnlineOrder order = order(OnlineOrderStatus.PENDING_PAYMENT);

        OnlineOrderResponse response = service.cancel(ONLINE_ID, "  Khách đổi ý  ");

        InOrder inOrder = inOrder(onlineOrders, payments, sales, stock);
        inOrder.verify(onlineOrders).findCustomerIdById(ONLINE_ID);
        inOrder.verify(onlineOrders).lockById(ONLINE_ID);
        inOrder.verify(onlineOrders).findById(ONLINE_ID);
        inOrder.verify(payments).hasSucceededForOnlineOrder(ONLINE_ID);
        inOrder.verify(sales).cancel(ORDER_ID, OnlineCancelType.CUSTOMER, ACCOUNT, "Khách đổi ý");
        inOrder.verify(payments).cancelPendingForOnlineOrder(ONLINE_ID);
        inOrder.verify(stock).release(new OrderRef(ONLINE_ID, ORDER_ID));
        inOrder.verify(onlineOrders).saveAndFlush(order);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Khách đổi ý");
        assertThat(order.getCancelledBy()).isEqualTo(ACCOUNT);
        assertThat(order.getCancelledAt()).isEqualTo(Instant.parse("2026-11-15T03:00:00.123456Z"));
        assertThat(response.status()).isEqualTo(OnlineOrderStatus.CANCELLED);
        assertThat(response.total()).isEqualTo(230_000);
    }

    @Test
    void cancelOfAPaidProcessingOrAnyOtherStateIs409AndWritesNothing() {
        for (OnlineOrderStatus status : OnlineOrderStatus.values()) {
            if (status == OnlineOrderStatus.PENDING_PAYMENT) {
                continue;
            }
            order(status);

            assertThatThrownBy(() -> service.cancel(ONLINE_ID, "Đổi ý")).as("%s", status)
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
        verifyNoInteractions(sales, stock, payments);
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void cancelWithASucceededTransactionIs409AndWritesNothing() {
        order(OnlineOrderStatus.PENDING_PAYMENT);
        when(payments.hasSucceededForOnlineOrder(ONLINE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.cancel(ONLINE_ID, "Đổi ý")).isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(sales, stock);
        verify(payments, never()).cancelPendingForOnlineOrder(anyLong());
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void cancelOfSomeoneElsesOrMissingOrderIs404BeforeAnyLock() {
        when(onlineOrders.findCustomerIdById(ONLINE_ID)).thenReturn(Optional.of(CUSTOMER + 1));
        assertThatThrownBy(() -> service.cancel(ONLINE_ID, "Đổi ý")).isInstanceOf(ResourceNotFoundException.class);

        when(onlineOrders.findCustomerIdById(ONLINE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(ONLINE_ID, "Đổi ý")).isInstanceOf(ResourceNotFoundException.class);

        verify(onlineOrders, never()).lockById(anyLong());
        verifyNoInteractions(sales, stock, payments);
    }

    // ================================================================ xác nhận đã nhận

    @Test
    void confirmReceivedMovesDeliveredToCompletedWithTheClock() {
        OnlineOrder order = order(OnlineOrderStatus.DELIVERED);

        OnlineOrderResponse response = service.confirmReceived(ONLINE_ID);

        InOrder inOrder = inOrder(onlineOrders);
        inOrder.verify(onlineOrders).lockById(ONLINE_ID);
        inOrder.verify(onlineOrders).findById(ONLINE_ID);
        inOrder.verify(onlineOrders).saveAndFlush(order);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isEqualTo(Instant.parse("2026-11-15T03:00:00.123456Z"));
        assertThat(response.status()).isEqualTo(OnlineOrderStatus.COMPLETED);
        verifyNoInteractions(stock, payments);
        verify(sales, never()).cancel(any(), any(), any(), any());
    }

    @Test
    void confirmReceivedInAnyOtherStateIs409AndWritesNothing() {
        for (OnlineOrderStatus status : OnlineOrderStatus.values()) {
            if (status == OnlineOrderStatus.DELIVERED) {
                continue;
            }
            order(status);

            assertThatThrownBy(() -> service.confirmReceived(ONLINE_ID)).as("%s", status)
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void confirmReceivedOfSomeoneElsesOrderIs404BeforeAnyLock() {
        when(onlineOrders.findCustomerIdById(ONLINE_ID)).thenReturn(Optional.of(CUSTOMER + 1));

        assertThatThrownBy(() -> service.confirmReceived(ONLINE_ID)).isInstanceOf(ResourceNotFoundException.class);

        verify(onlineOrders, never()).lockById(anyLong());
    }

    // ================================================================ xem

    @Test
    void getOfSomeoneElsesOrderIs404() {
        OnlineOrder order = order(OnlineOrderStatus.PAID);
        ReflectionTestUtils.setField(order, "customerId", CUSTOMER + 1);

        assertThatThrownBy(() -> service.get(ONLINE_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getBuildsTheLinesFromTheSalesOrderWithTheDisplayUnit() {
        order(OnlineOrderStatus.PAID);

        OnlineOrderResponse response = service.get(ONLINE_ID);

        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.unit()).isEqualTo("túi");
            assertThat(line.lineTotal()).isEqualTo(200_000);
        });
        assertThat(response.subtotal()).isEqualTo(200_000);
        assertThat(response.branchName()).isEqualTo("Chi nhánh 1");
    }
}
