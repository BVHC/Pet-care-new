package com.petcare.module.sales.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.dto.PaymentPreviewRequest;
import com.petcare.module.sales.dto.PaymentPreviewResponse;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.dto.ShortageLineResponse;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Đọc cho thu tiền (sales-v1 #12, #13, #15; UC70; docs/adr/0064). Chỉ đọc, không khóa, một snapshot REPEATABLE READ
 * (tổng và tồn của bản xem trước cùng một thời điểm). Xem trước không cần ca mở và không ghi gì (A2); lúc thu thật mọi
 * điều kiện được kiểm lại dưới khóa nên bản xem trước chỉ là gợi ý.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PaymentQueryService {

    private final SalesOrderRepository orders;
    private final PaymentRepository payments;
    private final StockQueryApi stockQuery;
    private final CustomerQueryApi customers;
    private final PaymentAssembler assembler;
    private final BranchScope branchScope;

    /** #13: tổng phải thu và các dòng thiếu tồn. Thứ tự lỗi: 404 → 403 chi nhánh → BR-TG-03 → BR-TG-01. */
    public PaymentPreviewResponse previewPayment(PaymentPreviewRequest request) {
        List<Long> ids = request.orderIds().stream().sorted().toList();
        List<SalesOrder> found = orders.findAllByIdOrdered(ids);
        if (found.size() < ids.size()) {
            Set<Long> present = new HashSet<>();
            found.forEach(order -> present.add(order.getId()));
            throw new ResourceNotFoundException("đơn hàng",
                    ids.stream().filter(id -> !present.contains(id)).findFirst().orElseThrow());
        }
        found.forEach(order -> branchScope.check(order.getBranchId()));
        if (found.stream().map(SalesOrder::getCustomerId).distinct().count() > 1
                || found.stream().map(SalesOrder::getBranchId).distinct().count() > 1) {
            throw new BusinessRuleViolationException("BR-TG-03",
                    "Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh");
        }
        if (found.stream().anyMatch(order -> order.getStatus() != OrderStatus.PENDING)) {
            throw new BusinessRuleViolationException("BR-TG-01", "Chỉ thu được Order đang chờ thu tiền");
        }
        Long branchId = found.get(0).getBranchId();
        Set<Long> productIds = PaymentLines.stockLines(found).stream().flatMap(sale -> sale.lines().stream())
                .map(line -> line.productId()).collect(Collectors.toSet());
        List<ShortageLineResponse> shortages = List.of();
        if (!productIds.isEmpty()) {
            Map<Long, Integer> available = stockQuery.availableQuantities(branchId, productIds);
            shortages = PaymentLines.shortageLines(found, available);
        }
        return new PaymentPreviewResponse(found.get(0).getCustomerId(), branchId, PaymentLines.total(found),
                shortages);
    }

    /** #12: Order {@code PENDING} của khách tại chi nhánh của lễ tân, chốt lâu nhất trước. */
    public List<OrderSummaryResponse> listPayableOrders(Long customerId) {
        Long branchId = branchScope.resolve(null);
        customers.findContact(customerId).orElseThrow(() -> new ResourceNotFoundException("khách hàng", customerId));
        List<SalesOrder> pending = orders.findByCustomerIdAndBranchIdAndStatusOrderByPendingAtAscIdAsc(customerId,
                branchId, OrderStatus.PENDING);
        return assembler.toSummaries(pending);
    }

    /** #15: phiếu thu cùng các Order của nó; 404 → 403 chi nhánh. */
    public PaymentResponse getPayment(Long paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("phiếu thu", paymentId));
        branchScope.check(payment.getBranchId());
        return assembler.toResponse(payment, orders.findByPaymentIdOrderById(paymentId));
    }
}
