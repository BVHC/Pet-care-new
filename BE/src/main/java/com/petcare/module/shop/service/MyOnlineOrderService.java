package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
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
import com.petcare.module.shop.dto.OnlineOrderLineResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.OnlineOrderSummaryResponse;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.mapper.OnlineOrderMapper;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Đơn online của tôi (UC64, BR-BH-09, BR-BH-10; docs/adr/0145): danh sách, chi tiết, hủy đơn chưa thanh toán (Đơn hàng
 * online#3) và xác nhận đã nhận (#11). Chỉ CUSTOMER, chỉ đơn của mình (đơn của người khác là 404, ADR-0006).
 * <p>
 * <b>Thứ tự khóa của ghi lên đơn online</b> (ADR-0145, ràng buộc cả task 126, 127, 138): {@code online_orders}
 * ({@code FOR NO KEY UPDATE}) → {@code orders} (sales, khóa trước khi đọc) → {@code payment_transactions} →
 * {@code stock_reservations} → {@code stock_lots} → {@code document_counters}. Hủy đơn: chủ đơn đọc không khóa (404 trước
 * mọi khóa) → khóa {@code online_orders} → đọc đơn dưới khóa, kiểm trạng thái / giao dịch {@code SUCCEEDED} → hủy Order →
 * hủy giao dịch {@code PENDING} → nhả giữ hàng → ghi đơn. Không thông báo (08 §B #3 không đòi) và không audit: khách tự
 * phục vụ như đặt đơn (ADR-0144).
 */
@Service
public class MyOnlineOrderService {

    static final int MAX_PAGE_SIZE = 100;
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OnlineOrderRepository onlineOrders;
    private final CustomerQueryApi customers;
    private final OnlineSalesOrderApi sales;
    private final StockApi stock;
    private final PaymentTransactionApi payments;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final OnlineOrderTransitionHandler fsm;
    private final OnlineOrderMapper mapper;
    private final BranchScope scope;
    private final Clock clock;

    public MyOnlineOrderService(OnlineOrderRepository onlineOrders, CustomerQueryApi customers,
            OnlineSalesOrderApi sales, StockApi stock, PaymentTransactionApi payments, BranchQueryApi branches,
            CatalogQueryApi catalog, OnlineOrderTransitionHandler fsm, OnlineOrderMapper mapper, BranchScope scope,
            Clock clock) {
        this.onlineOrders = onlineOrders;
        this.customers = customers;
        this.sales = sales;
        this.stock = stock;
        this.payments = payments;
        this.branches = branches;
        this.catalog = catalog;
        this.fsm = fsm;
        this.mapper = mapper;
        this.scope = scope;
        this.clock = clock;
    }

    // ================================================================ xem

    /** shop-v1 {@code listMyOnlineOrders}: đơn của khách, mới nhất trước; đếm và lấy trang cùng một snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OnlineOrderSummaryResponse> list(OnlineOrderStatus status, int page, int size) {
        Long customerId = ownCustomerId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), NEWEST_FIRST);
        Page<OnlineOrder> found = status == null ? onlineOrders.findByCustomerId(customerId, pageable)
                : onlineOrders.findByCustomerIdAndStatus(customerId, status, pageable);
        Map<Long, List<OnlineOrderLineInput>> lines = sales
                .findLines(found.getContent().stream().map(OnlineOrder::getOrderId).toList());
        Map<Long, String> branchNames = new HashMap<>();
        Page<OnlineOrderSummaryResponse> summaries = found.map(order -> {
            List<OnlineOrderLineInput> orderLines = lines.getOrDefault(order.getOrderId(), List.of());
            return mapper.toSummary(order, branchName(branchNames, order.getBranchId()), orderLines.size(),
                    subtotalOf(orderLines));
        });
        return PageResponse.of(summaries);
    }

    /** shop-v1 {@code getMyOnlineOrder}: đơn không có hoặc của khách khác → 404. */
    @Transactional(readOnly = true)
    public OnlineOrderResponse get(Long onlineOrderId) {
        Long customerId = ownCustomerId();
        OnlineOrder order = onlineOrders.findById(onlineOrderId)
                .filter(o -> o.getCustomerId().equals(customerId))
                .orElseThrow(() -> notFound(onlineOrderId));
        return toResponse(order);
    }

    // ================================================================ hủy

    /**
     * shop-v1 {@code cancelMyOnlineOrder} (Đơn hàng online#3): {@code PENDING_PAYMENT} → {@code CANCELLED}. Đơn ở trạng
     * thái khác, kể cả {@code PAID} / {@code PROCESSING} (#13 đã bỏ, ADR-0156), là 409. Giao dịch {@code SUCCEEDED}
     * đã có thì 409 (callback thanh toán đang chuyển đơn sang {@code PAID}).
     */
    @Transactional
    public OnlineOrderResponse cancel(Long onlineOrderId, String reason) {
        Long accountId = scope.current().accountId();
        OnlineOrder order = lockOwn(onlineOrderId);
        if (order.getStatus() != OnlineOrderStatus.PENDING_PAYMENT) {
            // #13 (PAID / PROCESSING) có trong FSM nhưng chưa làm: không để khách hủy đơn đã thu tiền khi chưa hoàn được
            throw new InvalidStateTransitionException(fsmName(), order.getStatus().name(),
                    OnlineOrderStatus.CANCELLED.name());
        }
        fsm.validateTransition(order.getStatus(), OnlineOrderStatus.CANCELLED);
        if (payments.hasSucceededForOnlineOrder(order.getId())) {
            throw new InvalidStateTransitionException(fsmName() + " (đã có giao dịch thanh toán thành công)",
                    order.getStatus().name(), OnlineOrderStatus.CANCELLED.name());
        }
        String cleanReason = reason.strip();
        // orders → payment_transactions → stock_reservations (ADR-0145)
        sales.cancel(order.getOrderId(), OnlineCancelType.CUSTOMER, accountId, cleanReason);
        payments.cancelPendingForOnlineOrder(order.getId());
        stock.release(new OrderRef(order.getId(), order.getOrderId()));
        order.cancelByCustomer(accountId, cleanReason, now());
        onlineOrders.saveAndFlush(order);
        return toResponse(order);
    }

    // ================================================================ xác nhận đã nhận

    /** shop-v1 {@code confirmOnlineOrderReceived} (Đơn hàng online#11): {@code DELIVERED} → {@code COMPLETED}. */
    @Transactional
    public OnlineOrderResponse confirmReceived(Long onlineOrderId) {
        OnlineOrder order = lockOwn(onlineOrderId);
        fsm.validateTransition(order.getStatus(), OnlineOrderStatus.COMPLETED);
        order.confirmReceived(now());
        onlineOrders.saveAndFlush(order);
        return toResponse(order);
    }

    // ================================================================ chung

    /**
     * Đơn của khách đang gọi, dưới khóa: chủ đơn đọc không khóa trước (đơn người khác / không có → 404 trước mọi khóa),
     * khóa {@code online_orders}, rồi mới nạp entity (không {@code findById} trước khóa: persistence context trả bản cũ).
     */
    private OnlineOrder lockOwn(Long onlineOrderId) {
        Long customerId = ownCustomerId();
        Long owner = onlineOrders.findCustomerIdById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
        if (!owner.equals(customerId)) {
            throw notFound(onlineOrderId);
        }
        onlineOrders.lockById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
        return onlineOrders.findById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
    }

    private OnlineOrderResponse toResponse(OnlineOrder order) {
        List<OnlineOrderLineInput> lines = sales.findLines(List.of(order.getOrderId()))
                .getOrDefault(order.getOrderId(), List.of());
        Map<Long, ProductSaleDisplay> display = catalog
                .findProductSaleDisplay(lines.stream().map(OnlineOrderLineInput::productId).toList());
        List<OnlineOrderLineResponse> responses = lines.stream().map(l -> {
            ProductSaleDisplay d = display.get(l.productId());
            return new OnlineOrderLineResponse(l.productId(), l.name(), d == null ? "" : d.unit(), l.unitPrice(),
                    l.quantity(), l.unitPrice() * l.quantity());
        }).toList();
        return mapper.toResponse(order, branchName(new HashMap<>(), order.getBranchId()), responses, subtotalOf(lines));
    }

    private String branchName(Map<Long, String> cache, Long branchId) {
        return cache.computeIfAbsent(branchId, id -> branches.findBranch(id).map(BranchQueryApi.BranchSummary::name)
                .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + id + " của đơn online không tồn tại")));
    }

    private static long subtotalOf(List<OnlineOrderLineInput> lines) {
        return lines.stream().mapToLong(l -> l.unitPrice() * l.quantity()).sum();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String fsmName() {
        return "đơn hàng online";
    }

    private static ResourceNotFoundException notFound(Long onlineOrderId) {
        return new ResourceNotFoundException("đơn hàng online", onlineOrderId);
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu hỏng (500), như giỏ hàng. */
    private Long ownCustomerId() {
        Long accountId = scope.current().accountId();
        return customers.findCustomerIdByAccountId(accountId).orElseThrow(
                () -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }
}
