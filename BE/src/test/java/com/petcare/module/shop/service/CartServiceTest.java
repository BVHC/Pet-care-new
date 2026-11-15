package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.shop.dto.CartResponse;
import com.petcare.module.shop.dto.SetCartItemRequest;
import com.petcare.module.shop.entity.CartItem;
import com.petcare.module.shop.mapper.CartMapperImpl;
import com.petcare.module.shop.repository.CartItemRepository;
import com.petcare.module.shop.repository.CartRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** Giỏ hàng (UC61, BR-BH-07; docs/adr/0141): thứ tự khóa → dọn → kiểm → ghi, và nhánh lỗi không ghi gì. */
class CartServiceTest {

    private static final long ACCOUNT = 5L;
    private static final long CUSTOMER = 9L;
    private static final long CART = 77L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-15T03:00:00.123456Z");

    private final CartRepository carts = mock(CartRepository.class);
    private final CartItemRepository items = mock(CartItemRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final StockQueryApi stock = mock(StockQueryApi.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private CartService service;
    private List<CartItem> stored;

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(carts.findIdByCustomerId(CUSTOMER)).thenReturn(Optional.of(CART));
        when(carts.lockIdByCustomerId(CUSTOMER)).thenReturn(Optional.of(CART));
        stored = new ArrayList<>();
        when(items.findByCartIdOrderById(CART)).thenAnswer(call -> List.copyOf(stored));
        when(catalog.findProducts(anyCollection())).thenAnswer(call -> {
            List<ProductInfo> found = new ArrayList<>();
            for (Object id : (java.util.Collection<?>) call.getArgument(0)) {
                if (id instanceof Long l && products.containsKey(l)) {
                    found.add(products.get(l));
                }
            }
            return found;
        });
        when(catalog.findProductSaleDisplay(anyCollection())).thenReturn(Map.of());
        when(stock.findChainAvailableQuantity(anyCollection())).thenReturn(Map.of());
        service = new CartService(carts, items, customers, catalog, stock, new CartMapperImpl(), scope,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private final Map<Long, ProductInfo> products = new java.util.HashMap<>();

    private ProductInfo product(long id, ProductType type, boolean prescription, boolean active, long price) {
        ProductInfo info = new ProductInfo(id, "SP" + id, type, prescription, false, null, price, active);
        products.put(id, info);
        when(catalog.findProduct(id)).thenReturn(Optional.of(info));
        return info;
    }

    private ProductInfo goods(long id, long price) {
        return product(id, ProductType.GOODS, false, true, price);
    }

    private void inCart(long productId, int quantity) {
        CartItem item = BeanUtils.instantiateClass(CartItem.class);
        ReflectionTestUtils.setField(item, "id", 1000L + stored.size());
        ReflectionTestUtils.setField(item, "cartId", CART);
        ReflectionTestUtils.setField(item, "productId", productId);
        ReflectionTestUtils.setField(item, "quantity", (short) quantity);
        stored.add(item);
    }

    // ================================================================ ghi: thứ tự

    @Test
    void setItemCreatesTheCartLocksItPurgesHiddenLinesChecksTheLimitThenWrites() {
        goods(1, 100);
        goods(2, 200);
        product(3, ProductType.GOODS, false, false, 300);
        inCart(2, 1);
        inCart(3, 4);

        service.setItem(1L, new SetCartItemRequest(3));

        InOrder order = inOrder(customers, catalog, carts, items);
        order.verify(customers).findCustomerIdByAccountId(ACCOUNT);
        order.verify(catalog).findProduct(1L);
        order.verify(carts).insertIfAbsent(CUSTOMER, NOW_MICROS);
        order.verify(carts).lockIdByCustomerId(CUSTOMER);
        order.verify(items).findByCartIdOrderById(CART);
        order.verify(items).deleteByCartIdAndProductIdIn(eq(CART), eq(List.of(3L)));
        order.verify(items).upsert(CART, 1L, 3, NOW_MICROS);
    }

    @Test
    void setItemDoesNotDeleteAnythingWhenEveryLineIsStillSellable() {
        goods(1, 100);
        goods(2, 200);
        inCart(2, 1);

        service.setItem(1L, new SetCartItemRequest(1));

        verify(items, never()).deleteByCartIdAndProductIdIn(anyLong(), anyCollection());
        verify(items).upsert(CART, 1L, 1, NOW_MICROS);
    }

    // ================================================================ ghi: giới hạn 30 dòng

    @Test
    void addingTheThirtyFirstProductIsBrBh07AndWritesNothing() {
        goods(100, 10);
        for (long id = 1; id <= 30; id++) {
            goods(id, 10);
            inCart(id, 1);
        }

        assertThatThrownBy(() -> service.setItem(100L, new SetCartItemRequest(1)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-07");

        verify(items, never()).upsert(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    void updatingAnExistingLineAtThirtyLinesIsAllowed() {
        for (long id = 1; id <= 30; id++) {
            goods(id, 10);
            inCart(id, 1);
        }

        service.setItem(7L, new SetCartItemRequest(5));

        verify(items).upsert(CART, 7L, 5, NOW_MICROS);
    }

    @Test
    void hiddenLinesDoNotCountTowardsTheLimit() {
        goods(100, 10);
        for (long id = 1; id <= 30; id++) {
            product(id, ProductType.GOODS, false, id > 5, 10);
            inCart(id, 1);
        }

        service.setItem(100L, new SetCartItemRequest(1));

        verify(items).deleteByCartIdAndProductIdIn(eq(CART), eq(List.of(1L, 2L, 3L, 4L, 5L)));
        verify(items).upsert(CART, 100L, 1, NOW_MICROS);
    }

    // ================================================================ ghi: sản phẩm không hợp lệ

    @Test
    void anUnknownOrInactiveProductIs404AndTouchesNoCart() {
        product(2, ProductType.GOODS, false, false, 10);
        when(catalog.findProduct(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setItem(1L, new SetCartItemRequest(1)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setItem(2L, new SetCartItemRequest(1)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(carts, items);
    }

    @Test
    void aDrugAVaccineOrAPrescriptionProductIsBrBh07AndTouchesNoCart() {
        product(1, ProductType.DRUG, false, true, 10);
        product(2, ProductType.VACCINE, false, true, 10);
        product(3, ProductType.GOODS, true, true, 10);

        for (long id : new long[] {1, 2, 3}) {
            assertThatThrownBy(() -> service.setItem(id, new SetCartItemRequest(1)))
                    .as("sản phẩm %d", id).isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("BR-BH-07");
        }

        verifyNoInteractions(carts, items);
    }

    // ================================================================ xóa

    @Test
    void removeItemLocksPurgesThenDeletesTheLine() {
        goods(1, 100);
        goods(2, 200);
        product(3, ProductType.GOODS, true, true, 300);
        inCart(1, 1);
        inCart(2, 1);
        inCart(3, 1);

        service.removeItem(1L);

        InOrder order = inOrder(carts, items);
        order.verify(carts).lockIdByCustomerId(CUSTOMER);
        order.verify(items).findByCartIdOrderById(CART);
        order.verify(items).deleteByCartIdAndProductIdIn(CART, List.of(3L));
        order.verify(items).deleteByCartIdAndProductIdIn(CART, List.of(1L));
    }

    @Test
    void removeItemOfACustomerWithoutACartWritesNothingAndReturnsAnEmptyCart() {
        when(carts.lockIdByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        CartResponse cart = service.removeItem(1L);

        assertThat(cart.items()).isEmpty();
        assertThat(cart.subtotal()).isZero();
        verifyNoInteractions(items);
        verify(carts, never()).insertIfAbsent(anyLong(), any());
    }

    @Test
    void clearLocksThenDeletesEveryLine() {
        service.clear();

        InOrder order = inOrder(carts, items);
        order.verify(carts).lockIdByCustomerId(CUSTOMER);
        order.verify(items).deleteByCartId(CART);
    }

    @Test
    void clearWithoutACartDoesNothing() {
        when(carts.lockIdByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        service.clear();

        verifyNoInteractions(items);
    }

    // ================================================================ xem

    @Test
    void getHidesIneligibleLinesFromItemsAndSubtotalAndWritesNothing() {
        goods(1, 100);
        product(2, ProductType.GOODS, false, false, 5_000);
        product(3, ProductType.DRUG, false, true, 7_000);
        product(4, ProductType.GOODS, true, true, 9_000);
        goods(5, 250);
        inCart(1, 2);
        inCart(2, 1);
        inCart(3, 1);
        inCart(4, 1);
        inCart(5, 3);
        when(catalog.findProductSaleDisplay(List.of(1L, 5L)))
                .thenReturn(Map.of(1L, new ProductSaleDisplay("hộp", "http://img/1"), 5L, new ProductSaleDisplay("gói", null)));
        when(stock.findChainAvailableQuantity(List.of(1L, 5L))).thenReturn(Map.of(1L, 2L, 5L, 2L));

        CartResponse cart = service.get();

        assertThat(cart.items()).extracting("productId").containsExactly(1L, 5L);
        assertThat(cart.items()).extracting("unitPrice").containsExactly(100L, 250L);
        assertThat(cart.items()).extracting("unit").containsExactly("hộp", "gói");
        assertThat(cart.items()).extracting("imageUrl").containsExactly("http://img/1", null);
        assertThat(cart.items()).extracting("available").containsExactly(true, false);
        assertThat(cart.subtotal()).isEqualTo(2 * 100 + 3 * 250);
        verify(items, never()).deleteByCartIdAndProductIdIn(anyLong(), anyCollection());
        verify(carts, never()).insertIfAbsent(anyLong(), any());
        verify(carts, never()).lockIdByCustomerId(anyLong());
    }

    @Test
    void getOfACustomerWithoutACartIsEmptyAndTouchesNoItem() {
        when(carts.findIdByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        CartResponse cart = service.get();

        assertThat(cart.items()).isEmpty();
        assertThat(cart.subtotal()).isZero();
        verifyNoInteractions(items, stock);
    }

    @Test
    void anAccountWithoutACustomerProfileIsADataFault() {
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.setItem(1L, new SetCartItemRequest(1)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoMoreInteractions(carts, items);
    }
}
