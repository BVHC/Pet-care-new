package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.shop.dto.CartItemResponse;
import com.petcare.module.shop.dto.CartResponse;
import com.petcare.module.shop.dto.SetCartItemRequest;
import com.petcare.module.shop.entity.CartItem;
import com.petcare.module.shop.mapper.CartMapper;
import com.petcare.module.shop.repository.CartItemRepository;
import com.petcare.module.shop.repository.CartRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Giỏ hàng của khách (UC61, BR-BH-07, BR-SP-01; docs/adr/0141). Mỗi khách một giỏ lưu ở server, tạo lười ở lần ghi đầu
 * tiên. Một dòng chỉ được tính khi sản phẩm còn bán được ({@link #eligible}: đang kinh doanh, {@code GOODS}, không kê đơn).
 * <p>
 * Ghi = một use case một transaction: xác định khách (tài khoản hiện tại) → kiểm sản phẩm khi thêm → tạo giỏ nếu chưa có →
 * khóa dòng {@code carts} {@code FOR NO KEY UPDATE} → dọn các dòng không còn bán được → kiểm giới hạn 30 dòng → ghi
 * {@code cart_items} bằng lệnh tường minh → dựng giỏ. Không khóa gì khác, không audit (giỏ là dữ liệu riêng của khách),
 * không outbox. Xem (GET) chỉ đọc: loại các dòng không còn bán được khỏi kết quả mà không ghi.
 */
@Service
public class CartService {

    /** BR-BH-07: tối đa 30 dòng (số sản phẩm khác nhau). */
    static final int MAX_LINES = 30;
    static final String RULE = "BR-BH-07";

    private final CartRepository carts;
    private final CartItemRepository items;
    private final CustomerQueryApi customers;
    private final CatalogQueryApi catalog;
    private final StockQueryApi stock;
    private final CartMapper mapper;
    private final BranchScope scope;
    private final Clock clock;

    public CartService(CartRepository carts, CartItemRepository items, CustomerQueryApi customers,
            CatalogQueryApi catalog, StockQueryApi stock, CartMapper mapper, BranchScope scope, Clock clock) {
        this.carts = carts;
        this.items = items;
        this.customers = customers;
        this.catalog = catalog;
        this.stock = stock;
        this.mapper = mapper;
        this.scope = scope;
        this.clock = clock;
    }

    /** shop-v1 {@code getMyCart}: khách chưa có giỏ nhận giỏ rỗng, không ghi gì. */
    @Transactional(readOnly = true)
    public CartResponse get() {
        Long customerId = ownCustomerId();
        return carts.findIdByCustomerId(customerId).map(this::view).orElseGet(CartService::empty);
    }

    /** shop-v1 {@code setCartItem}: đặt số lượng (upsert, idempotent). */
    @Transactional
    public CartResponse setItem(Long productId, SetCartItemRequest request) {
        Long customerId = ownCustomerId();
        requireEligible(productId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        carts.insertIfAbsent(customerId, now);
        Long cartId = carts.lockIdByCustomerId(customerId)
                .orElseThrow(() -> new IllegalStateException("Giỏ của khách #" + customerId + " không tạo được"));
        List<CartItem> remaining = purgeIneligible(cartId);
        boolean exists = remaining.stream().anyMatch(item -> item.getProductId().equals(productId));
        if (!exists && remaining.size() >= MAX_LINES) {
            throw new BusinessRuleViolationException(RULE, "Giỏ hàng tối đa " + MAX_LINES + " sản phẩm khác nhau");
        }
        items.upsert(cartId, productId, request.quantity(), now);
        return view(cartId);
    }

    /** shop-v1 {@code removeCartItem}: sản phẩm không có trong giỏ vẫn trả giỏ hiện tại (idempotent). */
    @Transactional
    public CartResponse removeItem(Long productId) {
        Long customerId = ownCustomerId();
        Long cartId = carts.lockIdByCustomerId(customerId).orElse(null);
        if (cartId == null) {
            return empty();
        }
        purgeIneligible(cartId);
        items.deleteByCartIdAndProductIdIn(cartId, List.of(productId));
        return view(cartId);
    }

    /** shop-v1 {@code clearMyCart}: xóa mọi dòng, giữ dòng giỏ (idempotent). */
    @Transactional
    public void clear() {
        Long customerId = ownCustomerId();
        carts.lockIdByCustomerId(customerId).ifPresent(items::deleteByCartId);
    }

    /** Sản phẩm thêm vào giỏ: không thấy hoặc ngừng kinh doanh → 404; không phải GOODS hoặc kê đơn → 400 BR-BH-07. */
    private void requireEligible(Long productId) {
        ProductInfo info = catalog.findProduct(productId).filter(ProductInfo::active)
                .orElseThrow(() -> new ResourceNotFoundException("sản phẩm đang kinh doanh", productId));
        if (info.type() != ProductType.GOODS) {
            throw new BusinessRuleViolationException(RULE, "Chỉ bán online hàng hóa thường, không bán thuốc hay vaccine");
        }
        if (info.prescription()) {
            throw new BusinessRuleViolationException(RULE, "Sản phẩm kê đơn không bán online");
        }
    }

    private static boolean eligible(ProductInfo info) {
        return info != null && info.active() && info.type() == ProductType.GOODS && !info.prescription();
    }

    /**
     * Xóa các dòng mà sản phẩm không còn bán được (dưới khóa giỏ), để dòng ẩn không chiếm hạn mức 30 dòng. Trả các dòng còn lại.
     */
    private List<CartItem> purgeIneligible(Long cartId) {
        List<CartItem> all = items.findByCartIdOrderById(cartId);
        Map<Long, ProductInfo> infos = productsOf(all);
        List<Long> stale = all.stream().map(CartItem::getProductId).filter(id -> !eligible(infos.get(id))).toList();
        if (stale.isEmpty()) {
            return all;
        }
        items.deleteByCartIdAndProductIdIn(cartId, stale);
        return all.stream().filter(item -> !stale.contains(item.getProductId())).collect(Collectors.toList());
    }

    /** Dựng giỏ: chỉ dòng còn bán được; giá hiện tại; {@code available} theo tồn toàn chuỗi (đã trừ phần giữ). */
    private CartResponse view(Long cartId) {
        List<CartItem> all = items.findByCartIdOrderById(cartId);
        Map<Long, ProductInfo> infos = productsOf(all);
        List<CartItem> shown = all.stream().filter(item -> eligible(infos.get(item.getProductId()))).toList();
        if (shown.isEmpty()) {
            return empty();
        }
        List<Long> ids = shown.stream().map(CartItem::getProductId).toList();
        Map<Long, ProductSaleDisplay> display = catalog.findProductSaleDisplay(ids);
        Map<Long, Long> available = stock.findChainAvailableQuantity(ids);
        List<CartItemResponse> lines = new ArrayList<>();
        long subtotal = 0;
        for (CartItem item : shown) {
            ProductInfo info = infos.get(item.getProductId());
            boolean inStock = available.getOrDefault(item.getProductId(), 0L) >= item.getQuantity();
            lines.add(mapper.toItem(item, info, display.get(item.getProductId()), inStock));
            subtotal += info.price() * item.getQuantity();
        }
        return new CartResponse(lines, subtotal);
    }

    private Map<Long, ProductInfo> productsOf(Collection<CartItem> cartItems) {
        if (cartItems.isEmpty()) {
            return Map.of();
        }
        return catalog.findProducts(cartItems.stream().map(CartItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(ProductInfo::productId, Function.identity()));
    }

    private static CartResponse empty() {
        return new CartResponse(List.of(), 0);
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500), như lịch hẹn của khách. */
    private Long ownCustomerId() {
        Long accountId = scope.current().accountId();
        return customers.findCustomerIdByAccountId(accountId).orElseThrow(
                () -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }
}
