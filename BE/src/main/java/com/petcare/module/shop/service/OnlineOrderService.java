package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductSaleDisplay;
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
import com.petcare.module.inventory.api.StockApi.OrderRef;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineOrderLineInput;
import com.petcare.module.shop.dto.FulfillmentBranchResponse;
import com.petcare.module.shop.dto.OnlineOrderLineResponse;
import com.petcare.module.shop.dto.OnlineOrderQuoteResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.PlaceOnlineOrderRequest;
import com.petcare.module.shop.entity.CartItem;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.mapper.OnlineOrderMapper;
import com.petcare.module.shop.repository.CartItemRepository;
import com.petcare.module.shop.repository.CartRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * Đặt hàng online từ giỏ (UC62, BR-BH-08, BR-BH-09, ST09; docs/adr/0144): danh sách chi nhánh xử lý, báo giá và đặt đơn.
 * <p>
 * <b>Đặt đơn là một transaction</b>, thứ tự khóa (P1, ADR-0144): dòng {@code carts} của khách {@code FOR NO KEY UPDATE}
 * → đọc chi nhánh (phải {@code ACTIVE}; không {@code lockForBooking}) → địa chỉ (đọc) → {@code products FOR SHARE} của
 * các dòng (id tăng dần: chốt giá, kiểm lại điều kiện bán online) → mọi {@code stock_lots} của các sản phẩm tại chi nhánh
 * trong <b>một</b> câu ({@link StockApi#lockAvailableQuantity}) → tất cả dòng đủ hàng hoặc 400 {@code BR-BH-08} (chưa ghi
 * gì) → {@code document_counters} (hai mã) → INSERT {@code orders} + {@code order_lines} (sales) → INSERT
 * {@code online_orders} → INSERT {@code stock_reservations} ({@link StockApi#reserve}) → xóa đúng các dòng giỏ đã đặt →
 * outbox. Báo giá và danh sách chi nhánh chỉ đọc: không khóa, không ghi.
 * <p>
 * Không audit: khách tự phục vụ, như giỏ hàng (ADR-0143) và đặt lịch của khách. Phí vận chuyển nằm ở
 * {@code online_orders.shipping_fee}, không phải dòng Order (P6).
 */
@Service
public class OnlineOrderService {

    static final String RULE = "BR-BH-08";
    /** Order bán hàng dùng dãy mã chung với Order tại quầy (ADR-0043): {@code DH-yyMM-00001}. */
    /** Mã đơn online riêng (P5): {@code DO-yyMM-00001}; tiền tố khác {@code DH} để không nhầm với mã Order. */
    static final String ONLINE_DOC_TYPE = "ONLINE_ORDER";
    static final String ONLINE_PREFIX = "DO";
    static final int MAX_ADDRESS_LENGTH = 300;
    static final String TEMPLATE = "ONLINE_ORDER_PLACED";
    static final String TEMPLATE_APP = "ONLINE_ORDER_PLACED_APP";

    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final OnlineOrderRepository onlineOrders;
    private final CustomerQueryApi customers;
    private final CatalogQueryApi catalog;
    private final StockApi stock;
    private final StockQueryApi stockQuery;
    private final BranchQueryApi branches;
    private final OnlineSalesOrderApi sales;
    private final ShippingFeeApi shippingFees;
    private final SystemConfigApi config;
    private final NotificationApi notifications;
    private final AccountContactApi accounts;
    private final DocumentCodeGenerator codes;
    private final OnlineOrderMapper mapper;
    private final BranchScope scope;
    private final Clock clock;

    public OnlineOrderService(CartRepository carts, CartItemRepository cartItems, OnlineOrderRepository onlineOrders,
            CustomerQueryApi customers, CatalogQueryApi catalog, StockApi stock, StockQueryApi stockQuery,
            BranchQueryApi branches, OnlineSalesOrderApi sales, ShippingFeeApi shippingFees, SystemConfigApi config,
            NotificationApi notifications, AccountContactApi accounts, DocumentCodeGenerator codes,
            OnlineOrderMapper mapper, BranchScope scope, Clock clock) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.onlineOrders = onlineOrders;
        this.customers = customers;
        this.catalog = catalog;
        this.stock = stock;
        this.stockQuery = stockQuery;
        this.branches = branches;
        this.sales = sales;
        this.shippingFees = shippingFees;
        this.config = config;
        this.notifications = notifications;
        this.accounts = accounts;
        this.codes = codes;
        this.mapper = mapper;
        this.scope = scope;
        this.clock = clock;
    }

    /** Một dòng giỏ đủ điều kiện bán online, kèm giá để tính tiền (giá hiện tại khi xem, giá chốt khi đặt). */
    record Line(Long productId, String name, int quantity, long unitPrice) {
        long total() {
            return unitPrice * quantity;
        }
    }

    // ================================================================ chi nhánh xử lý

    /** shop-v1 {@code listFulfillmentBranches}: chi nhánh ACTIVE còn đủ hàng (đã trừ phần giữ) cho mọi dòng; giỏ rỗng → rỗng. */
    @Transactional(readOnly = true)
    public List<FulfillmentBranchResponse> listFulfillmentBranches() {
        Long customerId = ownCustomerId();
        List<Line> lines = readLines(carts.findIdByCustomerId(customerId).orElse(null));
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = lines.stream().map(Line::productId).toList();
        List<FulfillmentBranchResponse> result = new ArrayList<>();
        for (BranchSummary branch : branches.listActiveBranches()) {
            if (shortages(lines, stockQuery.findAvailableQuantity(branch.branchId(), productIds)).isEmpty()) {
                result.add(new FulfillmentBranchResponse(branch.branchId(), branch.name(), branch.address()));
            }
        }
        return result;
    }

    // ================================================================ báo giá

    /** shop-v1 {@code quoteOnlineOrder}: cùng kiểm tra như đặt đơn nhưng không khóa, không giữ hàng, không ghi gì. */
    @Transactional(readOnly = true)
    public OnlineOrderQuoteResponse quote(Long branchId, Long addressId) {
        Long customerId = ownCustomerId();
        requireActiveBranch(branchId);
        requireAddress(customerId, addressId);
        List<Line> lines = readLines(carts.findIdByCustomerId(customerId).orElse(null));
        requireLines(lines);
        requireStock(lines, stockQuery.findAvailableQuantity(branchId, productIdsOf(lines)));
        long subtotal = subtotalOf(lines);
        ShippingFeeQuote fee = shippingFees.quote(subtotal);
        return new OnlineOrderQuoteResponse(branchId, addressId, subtotal, fee.fee(), freeApplied(fee, subtotal),
                subtotal + fee.fee());
    }

    // ================================================================ đặt đơn

    /** shop-v1 {@code placeOnlineOrder} (Đơn hàng online#1, Order#11): — → PENDING_PAYMENT. */
    @Transactional
    public OnlineOrderResponse place(PlaceOnlineOrderRequest request) {
        Long accountId = scope.current().accountId();
        Long customerId = ownCustomerId();
        Long branchId = request.branchId();

        // 1. giỏ của khách (khóa đầu tiên); khách chưa có giỏ coi như giỏ rỗng
        Long cartId = carts.lockIdByCustomerId(customerId).orElse(null);
        // 2. chi nhánh (chỉ đọc) và địa chỉ của khách (chỉ đọc)
        BranchSummary branch = requireActiveBranch(branchId);
        AddressInfo address = requireAddress(customerId, request.addressId());
        String shipAddress = shipAddressOf(address);
        // 3. giỏ → các dòng bán được, products FOR SHARE (id tăng dần): giá chốt và kiểm lại điều kiện bán online
        List<Line> lines = lockLines(cartId);
        requireLines(lines);
        // 4. mọi lô của các sản phẩm tại chi nhánh trong một câu, rồi kiểm đủ hàng mọi dòng (không giữ một phần)
        requireStock(lines, stock.lockAvailableQuantity(branchId, productIdsOf(lines)));
        // 5. tiền, hạn thanh toán, mã chứng từ (document_counters sau khóa lô, trước mọi INSERT)
        long subtotal = subtotalOf(lines);
        ShippingFeeQuote fee = shippingFees.quote(subtotal);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant dueAt = now.plus(config.getInt(ConfigKey.SHOP_PAYMENT_WINDOW_HOURS), ChronoUnit.HOURS);
        LocalDate today = LocalDate.now(clock);
        String orderCode = codes.next(OnlineSalesOrderApi.ORDER_DOC_TYPE, OnlineSalesOrderApi.ORDER_PREFIX, today);
        String onlineCode = codes.next(ONLINE_DOC_TYPE, ONLINE_PREFIX, today);
        // 6. INSERT orders + order_lines (Order#11), online_orders, stock_reservations
        List<OnlineOrderLineInput> inputs = lines.stream()
                .map(l -> new OnlineOrderLineInput(l.productId(), l.name(), l.quantity(), l.unitPrice())).toList();
        Long orderId = sales.createPendingOrder(orderCode, branchId, customerId, accountId, inputs);
        OnlineOrder saved = onlineOrders.saveAndFlush(OnlineOrder.place(onlineCode, orderId, customerId, branchId,
                address.receiverName(), address.receiverPhone(), shipAddress, fee.fee(), dueAt));
        List<Shortage> missing = stock.reserve(branchId, new OrderRef(saved.getId(), orderId),
                lines.stream().map(l -> new SaleLine(l.productId(), l.quantity())).toList());
        if (!missing.isEmpty()) {
            // các lô đã khóa và đã kiểm ở bước 4: thiếu ở đây là lỗi lập trình, rollback cả transaction
            throw new IllegalStateException("Giữ hàng thiếu sau khi đã kiểm tồn: " + missing);
        }
        // 7. dọn đúng các dòng giỏ đã đặt (dòng ẩn không bán được ở lại giỏ)
        cartItems.deleteByCartIdAndProductIdIn(cartId, productIdsOf(lines));
        // 8. outbox
        notifyPlaced(customerId, accountId, onlineCode, subtotal + fee.fee(), dueAt);
        return toResponse(saved, branch, lines, subtotal);
    }

    // ================================================================ giỏ → dòng

    /** Các dòng của giỏ còn bán được, giá hiện tại, không khóa (xem / báo giá). */
    private List<Line> readLines(Long cartId) {
        if (cartId == null) {
            return List.of();
        }
        List<CartItem> all = cartItems.findByCartIdOrderById(cartId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductInfo> infos = catalog.findProducts(all.stream().map(CartItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(ProductInfo::productId, Function.identity()));
        return all.stream().filter(i -> CartService.eligible(infos.get(i.getProductId())))
                .map(i -> line(i, infos.get(i.getProductId()))).toList();
    }

    /** Như {@link #readLines} nhưng {@code products FOR SHARE} từng sản phẩm theo id tăng dần (chỉ gọi trong transaction đặt đơn). */
    private List<Line> lockLines(Long cartId) {
        if (cartId == null) {
            return List.of();
        }
        List<CartItem> all = new ArrayList<>(cartItems.findByCartIdOrderById(cartId));
        all.sort(Comparator.comparing(CartItem::getProductId));
        List<Line> lines = new ArrayList<>();
        for (CartItem item : all) {
            catalog.findProductForShare(item.getProductId()).filter(CartService::eligible)
                    .ifPresent(info -> lines.add(line(item, info)));
        }
        return lines;
    }

    private static Line line(CartItem item, ProductInfo info) {
        return new Line(info.productId(), info.name(), item.getQuantity(), info.price());
    }

    // ================================================================ kiểm tra

    private BranchSummary requireActiveBranch(Long branchId) {
        BranchSummary branch = branches.findBranch(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        if (branch.status() != BranchStatus.ACTIVE) {
            throw new BusinessRuleViolationException(RULE, "Chi nhánh chưa hoạt động, không thể xử lý đơn");
        }
        return branch;
    }

    /** Địa chỉ không có hoặc của khách khác → 404 (ADR-0006: dữ liệu người khác không lộ sự tồn tại). */
    private AddressInfo requireAddress(Long customerId, Long addressId) {
        return customers.findAddress(customerId, addressId)
                .orElseThrow(() -> new ResourceNotFoundException("địa chỉ giao hàng", addressId));
    }

    private static String shipAddressOf(AddressInfo a) {
        StringBuilder text = new StringBuilder(a.addressLine());
        if (a.ward() != null && !a.ward().isBlank()) {
            text.append(", ").append(a.ward());
        }
        text.append(", ").append(a.province());
        if (text.length() > MAX_ADDRESS_LENGTH) {
            throw new BusinessRuleViolationException(RULE,
                    "Địa chỉ giao hàng dài quá " + MAX_ADDRESS_LENGTH + " ký tự, vui lòng rút gọn trong sổ địa chỉ");
        }
        return text.toString();
    }

    private static void requireLines(List<Line> lines) {
        if (lines.isEmpty()) {
            throw new BusinessRuleViolationException(RULE, "Giỏ hàng trống hoặc không còn sản phẩm bán được");
        }
    }

    private static void requireStock(List<Line> lines, Map<Long, Integer> available) {
        List<String> missing = shortages(lines, available);
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException(RULE,
                    "Chi nhánh không đủ hàng: " + String.join("; ", missing));
        }
    }

    /** Tên các dòng thiếu hàng kèm số cần / số còn; rỗng = đủ mọi dòng. */
    private static List<String> shortages(List<Line> lines, Map<Long, Integer> available) {
        List<String> names = new ArrayList<>();
        for (Line line : lines) {
            int have = available.getOrDefault(line.productId(), 0);
            if (have < line.quantity()) {
                names.add(line.name() + " (cần " + line.quantity() + ", còn " + have + ")");
            }
        }
        return names;
    }

    private static List<Long> productIdsOf(List<Line> lines) {
        return lines.stream().map(Line::productId).toList();
    }

    private static long subtotalOf(List<Line> lines) {
        return lines.stream().mapToLong(Line::total).sum();
    }

    /** BR-SP-08: miễn phí vì đạt ngưỡng (ngưỡng &gt; 0 và tiền hàng &ge; ngưỡng), không phải vì phí cơ bản bằng 0. */
    private static boolean freeApplied(ShippingFeeQuote fee, long subtotal) {
        return fee.freeThreshold() > 0 && subtotal >= fee.freeThreshold();
    }

    // ================================================================ kết quả, thông báo

    private OnlineOrderResponse toResponse(OnlineOrder order, BranchSummary branch, List<Line> lines, long subtotal) {
        Map<Long, ProductSaleDisplay> display = catalog.findProductSaleDisplay(productIdsOf(lines));
        List<OnlineOrderLineResponse> responses = lines.stream().map(l -> {
            ProductSaleDisplay d = display.get(l.productId());
            return new OnlineOrderLineResponse(l.productId(), l.name(), d == null ? "" : d.unit(), l.unitPrice(),
                    l.quantity(), l.total());
        }).toList();
        return mapper.toResponse(order, branch.name(), responses, subtotal);
    }

    /**
     * ONLINE_ORDER_PLACED (V40): IN_APP tới tài khoản khách + EMAIL tới email của tài khoản (không gửi tới tài khoản
     * {@code PENDING}); đủ mọi biến bắt buộc của hai mẫu ({@code enqueue} không kiểm).
     */
    private void notifyPlaced(Long customerId, Long accountId, String code, long total, Instant dueAt) {
        String name = customers.findContact(customerId).map(CustomerContact::fullName).orElse("");
        Map<String, Object> payload = new HashMap<>();
        payload.put("ma_don", code);
        payload.put("tong_tien", String.format(Locale.of("vi", "VN"), "%,d đ", total));
        payload.put("han_thanh_toan", DUE_FORMAT.format(dueAt.atZone(TimeConfig.BUSINESS_ZONE)));
        payload.put("ten_khach", name);
        Optional<AccountContact> contact = accounts.findContacts(List.of(accountId)).stream().findFirst();
        if (contact.isPresent() && !contact.get().pending()) {
            notifications.enqueue(new NotificationRequest(TEMPLATE_APP, Channel.IN_APP, accountId, null, payload, null));
            notifications.enqueue(new NotificationRequest(TEMPLATE, Channel.EMAIL, null, contact.get().email(),
                    payload, null));
        }
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500), như giỏ hàng. */
    private Long ownCustomerId() {
        Long accountId = scope.current().accountId();
        return customers.findCustomerIdByAccountId(accountId).orElseThrow(
                () -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }
}
