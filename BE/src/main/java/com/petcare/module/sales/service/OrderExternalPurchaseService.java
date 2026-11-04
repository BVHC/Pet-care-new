package com.petcare.module.sales.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.dto.ShortageLineResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Chuyển dòng thuốc kê đơn thiếu tồn sang mua ngoài lúc thu (sales-v1 #11, UC70; BR-BH-04, BR-KB-03, BR-QT-15;
 * docs/adr/0103, 0076). Chỉ lễ tân (vai trò kiểm ở controller). Một {@code @Transactional}, không
 * {@code noRollbackFor}, không {@code catch}. Thứ tự: chi nhánh của Order đọc không khóa (404 → 403, như thu tiền) →
 * khóa Order → dòng thuộc Order (404) → {@code PENDING} (409) → dòng {@code DRUG} của Order {@code VISIT} và còn thiếu
 * tồn (400 {@code BR-BH-04}) → {@link PrescriptionApi#markExternalPurchaseAtPayment} <b>trước</b> khi xóa dòng Order
 * (FK {@code RESTRICT}) → flush → audit. Thiếu tồn xét trong phạm vi <b>Order này</b> (không tách dòng, BR-KB-03):
 * tồn đủ cho riêng Order mà chỉ thiếu khi thu gộp thì lễ tân thu riêng từng Order. Tồn đọc không khóa; #11 không đổi
 * kho và lúc thu mọi điều kiện được kiểm lại dưới khóa.
 */
@Service
@RequiredArgsConstructor
public class OrderExternalPurchaseService {

    private static final String EXTERNAL_REASON = "OUT_OF_STOCK_AT_PAYMENT";

    private final SalesOrderRepository orders;
    private final StockQueryApi stock;
    private final PrescriptionApi prescriptions;
    private final OrderAssembler assembler;
    private final AuditRecorder audit;
    private final BranchScope branchScope;

    @Transactional
    public OrderResponse moveDrugLineToExternalPurchase(Long orderId, Long lineId) {
        Long branchId = orders.findBranchIdById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(branchId);
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        OrderLine line = order.findLine(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("dòng đơn hàng", lineId));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "đơn hàng (chỉ chuyển mua ngoài khi Order đang chờ thu tiền)", order.getStatus().name(),
                    OrderStatus.PENDING.name());
        }
        if (line.getLineType() != OrderLineType.DRUG || order.getSource() != OrderSource.VISIT) {
            throw new BusinessRuleViolationException("BR-BH-04",
                    "Chỉ dòng thuốc kê đơn của Order khám mới chuyển được sang mua ngoài");
        }
        requireShort(order, line);

        SecurityPrincipal me = branchScope.current();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("lineId", line.getId());
        before.put("productId", line.getProductId());
        before.put("quantity", line.getQuantity());
        before.put("lineTotal", line.getLineTotal());
        before.put("totalAmount", order.getTotalAmount());

        prescriptions.markExternalPurchaseAtPayment(line.getId(), me.accountId());
        order.removeLine(line);
        orders.flush();

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("totalAmount", order.getTotalAmount());
        after.put("externalReason", EXTERNAL_REASON);
        audit.record(AuditEntry.of(SalesAuditActions.ORDER_LINE_MOVED_EXTERNAL).entity("orders", order.getId())
                .before(before).after(after));
        return assembler.assemble(order, me);
    }

    /** Chỉ cho chuyển khi chính dòng này thiếu theo tổng số lượng cần của sản phẩm trong Order (cùng công thức #13). */
    private void requireShort(SalesOrder order, OrderLine line) {
        Set<Long> productIds = PaymentLines.stockLines(List.of(order)).stream()
                .flatMap(sale -> sale.lines().stream()).map(SaleLine::productId).collect(Collectors.toSet());
        Map<Long, Integer> available = stock.availableQuantities(order.getBranchId(), productIds);
        boolean isShort = PaymentLines.shortageLines(List.of(order), available).stream()
                .map(ShortageLineResponse::lineId).anyMatch(line.getId()::equals);
        if (!isShort) {
            throw new BusinessRuleViolationException("BR-BH-04", "Tồn kho vẫn đủ cho dòng này nên không chuyển sang "
                    + "mua ngoài. Nếu Order được thu gộp với Order khác, hãy thu riêng từng Order");
        }
    }
}
