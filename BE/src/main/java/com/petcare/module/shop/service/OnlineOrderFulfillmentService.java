package com.petcare.module.shop.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineOrderLineInput;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.dto.OnlineOrderLineResponse;
import com.petcare.module.shop.dto.OnlineOrderResponse;
import com.petcare.module.shop.dto.OnlineOrderSummaryResponse;
import com.petcare.module.shop.dto.ShipRequest;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.mapper.OnlineOrderMapper;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Xử lý và giao đơn online của chi nhánh (UC65, BR-BH-11; docs/adr/0146): danh sách / chi tiết cho lễ tân và quản lý
 * chi nhánh, và Đơn hàng online#5 (bắt đầu xử lý), #6 (bàn giao), #7 (đã giao), #8 (giao thất bại), #9 (giao lại).
 * #10 (hủy sau giao thất bại, hoàn kho + hoàn tiền) chờ task 138. Không xuất kho ở đây: kho đã trừ FEFO lúc {@code PAID}
 * (BR-BH-09).
 * <p>
 * <b>Mỗi chuyển trạng thái một transaction</b>, thứ tự khóa của ADR-0145: chi nhánh của đơn đọc không khóa (404, rồi
 * 403 trước mọi khóa) → khóa {@code online_orders} ({@code FOR NO KEY UPDATE}) → nạp entity dưới khóa → trạng thái (409)
 * → đầu vào (400 {@code BR-BH-11}) → [CFG] giao lại tối đa (400) → ghi → thông báo (outbox). Không audit, như các
 * chuyển trạng thái khác của đơn online.
 */
@Service
public class OnlineOrderFulfillmentService {

    static final int MAX_PAGE_SIZE = 100;
    static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    /** Việc cần làm khi không lọc trạng thái (hợp đồng {@code listOnlineOrders}). */
    static final Set<OnlineOrderStatus> TO_DO = EnumSet.of(OnlineOrderStatus.PAID, OnlineOrderStatus.PROCESSING,
            OnlineOrderStatus.DELIVERY_FAILED);

    private final OnlineOrderRepository onlineOrders;
    private final OnlineSalesOrderApi sales;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi config;
    private final OnlineOrderNotices notices;
    private final OnlineOrderTransitionHandler fsm;
    private final OnlineOrderMapper mapper;
    private final BranchScope scope;
    private final Clock clock;

    public OnlineOrderFulfillmentService(OnlineOrderRepository onlineOrders, OnlineSalesOrderApi sales,
            BranchQueryApi branches, CatalogQueryApi catalog, SystemConfigApi config, OnlineOrderNotices notices,
            OnlineOrderTransitionHandler fsm, OnlineOrderMapper mapper, BranchScope scope, Clock clock) {
        this.onlineOrders = onlineOrders;
        this.sales = sales;
        this.branches = branches;
        this.catalog = catalog;
        this.config = config;
        this.notices = notices;
        this.fsm = fsm;
        this.mapper = mapper;
        this.scope = scope;
        this.clock = clock;
    }

    // ================================================================ xem

    /** shop-v1 {@code listOnlineOrders}: đơn của chi nhánh người gọi, cũ nhất trước; vắng {@code status} = việc cần làm. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OnlineOrderSummaryResponse> list(OnlineOrderStatus status, int page, int size) {
        Long branchId = scope.resolve(null);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), OLDEST_FIRST);
        Page<OnlineOrder> found = onlineOrders.findByBranchIdAndStatusIn(branchId,
                status == null ? TO_DO : EnumSet.of(status), pageable);
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

    /** shop-v1 {@code getOnlineOrder}: không có → 404; đơn của chi nhánh khác → 403 (BR-QT-03). */
    @Transactional(readOnly = true)
    public OnlineOrderResponse get(Long onlineOrderId) {
        OnlineOrder order = onlineOrders.findById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
        scope.check(order.getBranchId());
        return toResponse(order);
    }

    // ================================================================ chuyển trạng thái

    /** Đơn hàng online#5: {@code PAID} → {@code PROCESSING}. */
    @Transactional
    public OnlineOrderResponse startProcessing(Long onlineOrderId) {
        OnlineOrder order = lockOwnBranch(onlineOrderId);
        requireStatus(order, OnlineOrderStatus.PAID, OnlineOrderStatus.PROCESSING);
        order.startProcessing();
        onlineOrders.saveAndFlush(order);
        return toResponse(order);
    }

    /** Đơn hàng online#6: {@code PROCESSING} → {@code SHIPPED}; bắt buộc đơn vị vận chuyển và mã vận đơn; báo khách. */
    @Transactional
    public OnlineOrderResponse ship(Long onlineOrderId, ShipRequest request) {
        OnlineOrder order = lockOwnBranch(onlineOrderId);
        requireStatus(order, OnlineOrderStatus.PROCESSING, OnlineOrderStatus.SHIPPED);
        order.ship(required(request.carrier(), "đơn vị vận chuyển"), required(request.trackingCode(), "mã vận đơn"),
                now());
        onlineOrders.saveAndFlush(order);
        notices.shipped(order);
        return toResponse(order);
    }

    /** Đơn hàng online#7: {@code SHIPPED} → {@code DELIVERED}; {@code delivered_at} = bây giờ (BR-BH-12). */
    @Transactional
    public OnlineOrderResponse markDelivered(Long onlineOrderId) {
        OnlineOrder order = lockOwnBranch(onlineOrderId);
        requireStatus(order, OnlineOrderStatus.SHIPPED, OnlineOrderStatus.DELIVERED);
        order.markDelivered(now());
        onlineOrders.saveAndFlush(order);
        return toResponse(order);
    }

    /** Đơn hàng online#8: {@code SHIPPED} → {@code DELIVERY_FAILED}; lý do bắt buộc, lưu và báo khách. */
    @Transactional
    public OnlineOrderResponse failDelivery(Long onlineOrderId, String reason) {
        OnlineOrder order = lockOwnBranch(onlineOrderId);
        requireStatus(order, OnlineOrderStatus.SHIPPED, OnlineOrderStatus.DELIVERY_FAILED);
        String cleanReason = required(reason, "lý do giao thất bại");
        order.failDelivery(cleanReason);
        onlineOrders.saveAndFlush(order);
        notices.deliveryFailed(order, cleanReason);
        return toResponse(order);
    }

    /**
     * Đơn hàng online#9: {@code DELIVERY_FAILED} → {@code SHIPPED}, tối đa [CFG] {@code shop.max_redelivery} lần (đọc
     * lúc chạy, không chụp lại); thêm một lần giao lại, thay đơn vị vận chuyển / mã vận đơn, {@code shipped_at} = bây giờ.
     * Không có mẫu thông báo cho #9 (08 §B).
     */
    @Transactional
    public OnlineOrderResponse redeliver(Long onlineOrderId, ShipRequest request) {
        OnlineOrder order = lockOwnBranch(onlineOrderId);
        requireStatus(order, OnlineOrderStatus.DELIVERY_FAILED, OnlineOrderStatus.SHIPPED);
        String carrier = required(request.carrier(), "đơn vị vận chuyển");
        String trackingCode = required(request.trackingCode(), "mã vận đơn");
        int max = config.getInt(ConfigKey.SHOP_MAX_REDELIVERY);
        if (order.getRedeliveryCount() >= max) {
            throw new BusinessRuleViolationException("BR-BH-11",
                    "Đơn đã giao lại tối đa " + max + " lần, không giao lại thêm được");
        }
        order.redeliver(carrier, trackingCode, now());
        onlineOrders.saveAndFlush(order);
        return toResponse(order);
    }

    // ================================================================ chung

    /**
     * Đơn của chi nhánh người gọi, dưới khóa: chi nhánh đọc không khóa trước (không có → 404, chi nhánh khác → 403, cả hai
     * trước mọi khóa), khóa {@code online_orders}, rồi mới nạp entity (không {@code findById} trước khóa: persistence
     * context trả bản cũ).
     */
    private OnlineOrder lockOwnBranch(Long onlineOrderId) {
        Long branchId = onlineOrders.findBranchIdById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
        scope.check(branchId);
        onlineOrders.lockById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
        return onlineOrders.findById(onlineOrderId).orElseThrow(() -> notFound(onlineOrderId));
    }

    /**
     * Chỉ chuyển đúng một cạnh (từ {@code expected}) vì {@code SHIPPED} có hai cạnh vào (#6 và #9) mà FSM chung không
     * phân biệt; {@code validateTransition} vẫn chạy để đúng bảng 03 #14.
     */
    private void requireStatus(OnlineOrder order, OnlineOrderStatus expected, OnlineOrderStatus target) {
        if (order.getStatus() != expected) {
            throw new InvalidStateTransitionException("đơn hàng online", order.getStatus().name(), target.name());
        }
        fsm.validateTransition(order.getStatus(), target);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleViolationException("BR-BH-11", "Thiếu " + what);
        }
        return value.strip();
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

    private static ResourceNotFoundException notFound(Long onlineOrderId) {
        return new ResourceNotFoundException("đơn hàng online", onlineOrderId);
    }
}
