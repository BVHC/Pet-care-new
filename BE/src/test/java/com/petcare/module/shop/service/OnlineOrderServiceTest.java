package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.AddressInfo;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.fees.api.ShippingFeeApi;
import com.petcare.module.fees.api.ShippingFeeQuote;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.shop.dto.OnlineOrderQuoteResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.PlaceOnlineOrderRequest;
import com.petcare.module.shop.entity.CartItem;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.mapper.OnlineOrderMapperImpl;
import com.petcare.module.shop.repository.CartItemRepository;
import com.petcare.module.shop.repository.CartRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * Đặt hàng online (UC62, BR-BH-08, BR-BH-09; docs/adr/0144): thứ tự khóa của P1 (giỏ → chi nhánh → địa chỉ → sản phẩm →
 * lô → mã → INSERT → giữ hàng → dọn giỏ) và nhánh lỗi không ghi gì.
 */
class OnlineOrderServiceTest {

    private static final long ACCOUNT = 5L;
    private static final long CUSTOMER = 9L;
    private static final long CART = 77L;
    private static final long BRANCH = 3L;
    private static final long ADDRESS = 11L;
    private static final long ORDER_ID = 500L;
    private static final long ONLINE_ID = 600L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");

    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository cartItems = mock(CartItemRepository.class);
    private final OnlineOrderRepository onlineOrders = mock(OnlineOrderRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final StockApi stock = mock(StockApi.class);
    private final StockQueryApi stockQuery = mock(StockQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final OnlineSalesOrderApi sales = mock(OnlineSalesOrderApi.class);
    private final ShippingFeeApi fees = mock(ShippingFeeApi.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final DocumentCodeGenerator codes = mock(DocumentCodeGenerator.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final Map<Long, ProductInfo> products = new java.util.HashMap<>();
    private final List<CartItem> stored = new ArrayList<>();
    private OnlineOrderService service;

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(
                new CustomerContact(CUSTOMER, "Khách An", "0901", null, ACCOUNT, false)));
        when(carts.lockIdByCustomerId(CUSTOMER)).thenReturn(Optional.of(CART));
        when(carts.findIdByCustomerId(CUSTOMER)).thenReturn(Optional.of(CART));
        when(cartItems.findByCartIdOrderById(CART)).thenAnswer(call -> List.copyOf(stored));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        when(customers.findAddress(CUSTOMER, ADDRESS)).thenReturn(Optional.of(
                new AddressInfo(ADDRESS, "An", "0901234567", "12 Lê Lợi", "P. Bến Nghé", "TP.HCM")));
        when(catalog.findProducts(anyCollection())).thenAnswer(call -> {
            List<ProductInfo> found = new ArrayList<>();
            for (Object id : (java.util.Collection<?>) call.getArgument(0)) {
                if (products.containsKey((Long) id)) {
                    found.add(products.get((Long) id));
                }
            }
            return found;
        });
        when(catalog.findProductSaleDisplay(anyCollection())).thenReturn(Map.of());
        when(fees.quote(anyLong())).thenReturn(new ShippingFeeQuote(30_000, 30_000, 500_000));
        when(config.getInt(ConfigKey.SHOP_PAYMENT_WINDOW_HOURS)).thenReturn(24);
        when(codes.next(eq("ORDER"), eq("DH"), any())).thenReturn("DH-2611-00001");
        when(codes.next(eq("ONLINE_ORDER"), eq("DO"), any())).thenReturn("DO-2611-00001");
        when(sales.createPendingOrder(any(), anyLong(), anyLong(), anyLong(), anyList())).thenReturn(ORDER_ID);
        when(onlineOrders.saveAndFlush(any(OnlineOrder.class))).thenAnswer(call -> {
            OnlineOrder o = call.getArgument(0);
            ReflectionTestUtils.setField(o, "id", ONLINE_ID);
            ReflectionTestUtils.setField(o, "createdAt", NOW);
            ReflectionTestUtils.setField(o, "updatedAt", NOW);
            return o;
        });
        when(stock.reserve(anyLong(), any(), anyList())).thenReturn(List.of());
        when(accounts.findContacts(anyCollection())).thenReturn(List.of(new AccountContact(ACCOUNT, "an@example.com", false)));
        service = new OnlineOrderService(carts, cartItems, onlineOrders, customers, catalog, stock, stockQuery, branches,
                sales, fees, config, notifications, accounts, codes, new OnlineOrderMapperImpl(), scope,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BranchSummary branch(BranchStatus status) {
        return new BranchSummary(BRANCH, "Chi nhánh 3", "Địa chỉ", "0280", status, false);
    }

    private void goods(long id, long price, int quantity) {
        ProductInfo info = new ProductInfo(id, "SP" + id, ProductType.GOODS, false, false, null, price, true);
        products.put(id, info);
        when(catalog.findProductForShare(id)).thenReturn(Optional.of(info));
        CartItem item = BeanUtils.instantiateClass(CartItem.class);
        ReflectionTestUtils.setField(item, "id", 1000L + stored.size());
        ReflectionTestUtils.setField(item, "cartId", CART);
        ReflectionTestUtils.setField(item, "productId", id);
        ReflectionTestUtils.setField(item, "quantity", (short) quantity);
        stored.add(item);
    }

    private PlaceOnlineOrderRequest request() {
        return new PlaceOnlineOrderRequest(BRANCH, ADDRESS);
    }

    // ================================================================ đặt đơn

    @Test
    void placeFollowsTheLockOrderAndWritesEverythingOnce() {
        goods(20, 100_000, 2);
        goods(10, 40_000, 1);
        when(stock.lockAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 5, 20L, 5));

        OnlineOrderResponse response = service.place(request());

        InOrder order = inOrder(carts, branches, customers, catalog, stock, codes, sales, onlineOrders, cartItems,
                notifications);
        order.verify(carts).lockIdByCustomerId(CUSTOMER);
        order.verify(branches).findBranch(BRANCH);
        order.verify(customers).findAddress(CUSTOMER, ADDRESS);
        order.verify(catalog).findProductForShare(10L);
        order.verify(catalog).findProductForShare(20L);
        order.verify(stock).lockAvailableQuantity(eq(BRANCH), anyCollection());
        order.verify(codes).next(eq("ORDER"), eq("DH"), any());
        order.verify(codes).next(eq("ONLINE_ORDER"), eq("DO"), any());
        order.verify(sales).createPendingOrder(eq("DH-2611-00001"), eq(BRANCH), eq(CUSTOMER), eq(ACCOUNT), anyList());
        order.verify(onlineOrders).saveAndFlush(any(OnlineOrder.class));
        order.verify(stock).reserve(eq(BRANCH), eq(new StockApi.OrderRef(ONLINE_ID, ORDER_ID)), anyList());
        order.verify(cartItems).deleteByCartIdAndProductIdIn(eq(CART), eq(List.of(10L, 20L)));
        order.verify(notifications, org.mockito.Mockito.times(2)).enqueue(any());

        assertThat(response.code()).isEqualTo("DO-2611-00001");
        assertThat(response.salesOrderId()).isEqualTo(ORDER_ID);
        assertThat(response.subtotal()).isEqualTo(240_000);
        assertThat(response.shippingFee()).isEqualTo(30_000);
        assertThat(response.total()).isEqualTo(270_000);
        assertThat(response.paymentDueAt()).isEqualTo(Instant.parse("2026-11-16T03:00:00.123456Z"));
        assertThat(response.shipAddress()).isEqualTo("12 Lê Lợi, P. Bến Nghé, TP.HCM");
        assertThat(response.lines()).hasSize(2);
    }

    @Test
    void aShortLineRejectsWithBrBh08NamingItAndWritesNothingNorConsumesCodes() {
        goods(10, 40_000, 3);
        when(stock.lockAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 2));

        assertThatThrownBy(() -> service.place(request())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("SP10").hasMessageContaining("BR-BH-08");

        verify(codes, never()).next(any(), any(), any());
        verify(sales, never()).createPendingOrder(any(), anyLong(), anyLong(), anyLong(), anyList());
        verify(onlineOrders, never()).saveAndFlush(any());
        verify(stock, never()).reserve(anyLong(), any(), anyList());
        verify(cartItems, never()).deleteByCartIdAndProductIdIn(any(), anyCollection());
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void anEmptyCartAndACartWithOnlyIneligibleProductsAreRejectedBeforeAnyStockLock() {
        goods(10, 40_000, 1);
        when(catalog.findProductForShare(10L)).thenReturn(Optional.of(
                new ProductInfo(10L, "SP10", ProductType.DRUG, false, false, null, 40_000, true)));

        assertThatThrownBy(() -> service.place(request())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-BH-08");

        verify(stock, never()).lockAvailableQuantity(anyLong(), anyCollection());
        stored.clear();
        when(carts.lockIdByCustomerId(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.place(request())).isInstanceOf(BusinessRuleViolationException.class);
        verify(stock, never()).lockAvailableQuantity(anyLong(), anyCollection());
    }

    @Test
    void aMissingBranchIs404AndANonActiveBranchIs400BeforeTheAddressAndTheStock() {
        goods(10, 40_000, 1);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.place(request())).isInstanceOf(ResourceNotFoundException.class);

        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        assertThatThrownBy(() -> service.place(request())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-BH-08");

        verify(customers, never()).findAddress(anyLong(), anyLong());
        verify(stock, never()).lockAvailableQuantity(anyLong(), anyCollection());
    }

    @Test
    void anAddressOfSomeoneElseIs404AndAnOverlongSnapshotIs400() {
        goods(10, 40_000, 1);
        when(customers.findAddress(CUSTOMER, ADDRESS)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.place(request())).isInstanceOf(ResourceNotFoundException.class);

        when(customers.findAddress(CUSTOMER, ADDRESS)).thenReturn(Optional.of(
                new AddressInfo(ADDRESS, "An", "0901", "x".repeat(300), null, "TP.HCM")));
        assertThatThrownBy(() -> service.place(request())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-BH-08");

        verify(stock, never()).lockAvailableQuantity(anyLong(), anyCollection());
        verify(sales, never()).createPendingOrder(any(), anyLong(), anyLong(), anyLong(), anyList());
    }

    @Test
    void aReservationShortageAfterTheCheckIsAProgrammingErrorThatRollsBack() {
        goods(10, 40_000, 1);
        when(stock.lockAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 5));
        when(stock.reserve(anyLong(), any(), anyList())).thenReturn(List.of(new StockApi.Shortage(10L, 1, 0)));

        assertThatThrownBy(() -> service.place(request())).isInstanceOf(IllegalStateException.class);

        verify(cartItems, never()).deleteByCartIdAndProductIdIn(any(), anyCollection());
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void aPendingAccountGetsNoNotification() {
        goods(10, 40_000, 1);
        when(stock.lockAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 5));
        when(accounts.findContacts(anyCollection())).thenReturn(List.of(new AccountContact(ACCOUNT, "an@example.com", true)));

        service.place(request());

        verify(notifications, never()).enqueue(any());
    }

    // ================================================================ báo giá / chi nhánh

    @Test
    void quoteWritesNothingAndFlagsFreeShippingOnlyWhenTheThresholdIsReached() {
        goods(10, 100_000, 2);
        when(stockQuery.findAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 5));

        OnlineOrderQuoteResponse quote = service.quote(BRANCH, ADDRESS);
        assertThat(quote.freeShippingApplied()).isFalse();

        when(fees.quote(200_000)).thenReturn(new ShippingFeeQuote(0, 30_000, 150_000));
        quote = service.quote(BRANCH, ADDRESS);
        assertThat(quote.freeShippingApplied()).isTrue();
        assertThat(quote.shippingFee()).isZero();
        assertThat(quote.total()).isEqualTo(200_000);

        // phí cơ bản 0 và không ngưỡng: phí bằng 0 nhưng không phải "miễn phí vì đạt ngưỡng"
        when(fees.quote(200_000)).thenReturn(new ShippingFeeQuote(0, 0, 0));
        assertThat(service.quote(BRANCH, ADDRESS).freeShippingApplied()).isFalse();

        verify(carts, never()).lockIdByCustomerId(anyLong());
        verify(stock, never()).lockAvailableQuantity(anyLong(), anyCollection());
        verify(stock, never()).reserve(anyLong(), any(), anyList());
        verify(codes, never()).next(any(), any(), any());
        verify(sales, never()).createPendingOrder(any(), anyLong(), anyLong(), anyLong(), anyList());
    }

    @Test
    void quoteRejectsAnEmptyCartAndAShortBranch() {
        assertThatThrownBy(() -> service.quote(BRANCH, ADDRESS)).isInstanceOf(BusinessRuleViolationException.class);

        goods(10, 1_000, 4);
        when(stockQuery.findAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 3));
        assertThatThrownBy(() -> service.quote(BRANCH, ADDRESS)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("SP10");
    }

    @Test
    void fulfillmentBranchesAreTheActiveOnesWithEveryLineInStock() {
        goods(10, 1_000, 2);
        BranchSummary other = new BranchSummary(4L, "Chi nhánh 4", "Đ4", "0", BranchStatus.ACTIVE, false);
        when(branches.listActiveBranches()).thenReturn(List.of(branch(BranchStatus.ACTIVE), other));
        when(stockQuery.findAvailableQuantity(eq(BRANCH), anyCollection())).thenReturn(Map.of(10L, 2));
        when(stockQuery.findAvailableQuantity(eq(4L), anyCollection())).thenReturn(Map.of(10L, 1));

        assertThat(service.listFulfillmentBranches()).extracting("branchId").containsExactly(BRANCH);

        stored.clear();
        assertThat(service.listFulfillmentBranches()).isEmpty();
    }
}
