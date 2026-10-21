package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderSale;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.module.sales.dto.CreatePaymentRequest;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.OpenShiftRow;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thu tiền một hoặc nhiều Order {@code PENDING} của cùng khách, cùng chi nhánh (sales-v1 #14, UC70, Order#8; BR-TG-01…04,
 * BR-BH-04, 06, BR-QT-15; docs/adr/0064). Lễ tân có ca {@code OPEN} của chính mình. Một {@code @Transactional}, READ
 * COMMITTED (chờ khóa ca / Order rồi phải đọc lại trạng thái mới), không {@code noRollbackFor}, không {@code catch}.
 * <p>
 * Thứ tự khóa: ca {@code OPEN FOR SHARE} → mọi Order trong <b>một</b> câu theo id tăng dần → mọi lô của hợp sản phẩm
 * trong <b>một</b> câu ({@link StockApi#issueForPayment}) → {@code document_counters} (sinh mã cuối cùng). Thứ tự kiểm:
 * BR-TG-01 → 404 → 403 chi nhánh → 409 không {@code PENDING} → BR-TG-03 → BR-TG-02 → BR-BH-04 (thiếu tồn: không trừ gì,
 * rollback). Gửi lại sau khi đã thu nhận 409 (Order không còn {@code PENDING}) nên không thu hai lần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final CashierShiftRepository shifts;
    private final SalesOrderRepository orders;
    private final PaymentRepository payments;
    private final StockApi stock;
    private final DocumentCodeGenerator codes;
    private final OrderTransitionHandler orderFsm;
    private final PaymentAssembler assembler;
    private final AuditRecorder audit;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional
    public PaymentResponse collectPayment(CreatePaymentRequest request) {
        SecurityPrincipal me = branchScope.current();
        OpenShiftRow shift = shifts.lockOpenShiftForShare(me.accountId())
                .orElseThrow(() -> new BusinessRuleViolationException("BR-TG-01", "Bạn chưa mở ca thu ngân"));

        List<SalesOrder> locked = lockOrders(request.orderIds());
        locked.forEach(order -> branchScope.check(order.getBranchId()));
        locked.stream().filter(order -> order.getStatus() != OrderStatus.PENDING).findFirst().ifPresent(order -> {
            throw new InvalidStateTransitionException("đơn hàng (chỉ thu được Order đang chờ thu tiền)",
                    order.getStatus().name(), OrderStatus.PAID.name());
        });
        Set<Long> customerIds = locked.stream().map(SalesOrder::getCustomerId).collect(Collectors.toSet());
        Set<Long> branchIds = locked.stream().map(SalesOrder::getBranchId).collect(Collectors.toSet());
        if (customerIds.size() > 1 || branchIds.size() > 1) {
            throw new BusinessRuleViolationException("BR-TG-03",
                    "Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh");
        }
        long total = PaymentLines.total(locked);
        if (request.amount() != total) {
            throw new BusinessRuleViolationException("BR-TG-02",
                    "Số tiền thu (" + request.amount() + ") khác tổng phải trả (" + total + ")");
        }
        Long branchId = locked.get(0).getBranchId();
        Long customerId = locked.get(0).getCustomerId();

        List<OrderSale> stockLines = PaymentLines.stockLines(locked);
        if (!stockLines.isEmpty()) {
            List<Shortage> shortages = stock.issueForPayment(branchId, stockLines, me.accountId());
            if (!shortages.isEmpty()) {
                throw new BusinessRuleViolationException("BR-BH-04", shortageMessage(locked, shortages));
            }
        }

        locked.forEach(order -> orderFsm.validateTransition(order.getStatus(), OrderStatus.PAID));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        String code = codes.next("payments", "PT", LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE));
        Payment payment = payments.saveAndFlush(Payment.record(code, branchId, customerId, shift.getId(),
                request.method(), total, me.accountId(), now));
        locked.forEach(order -> order.markPaid(payment.getId(), now));
        orders.flush();
        locked.forEach(order -> auditPaid(order, payment));
        log.info("PAYMENT_COLLECTED paymentId={} code={} shiftId={} amount={} orders={}", payment.getId(), code,
                shift.getId(), total, request.orderIds());
        return assembler.toResponse(payment, locked);
    }

    private List<SalesOrder> lockOrders(List<Long> requested) {
        List<Long> ids = requested.stream().sorted().toList();
        List<SalesOrder> found = orders.findAllByIdForUpdate(ids).stream()
                .sorted(Comparator.comparing(SalesOrder::getId)).toList();
        if (found.size() < ids.size()) {
            Set<Long> present = new HashSet<>();
            found.forEach(order -> present.add(order.getId()));
            Long missing = ids.stream().filter(id -> !present.contains(id)).findFirst().orElseThrow();
            throw new ResourceNotFoundException("đơn hàng", missing);
        }
        return found;
    }

    private static String shortageMessage(List<SalesOrder> locked, List<Shortage> shortages) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (SalesOrder order : locked) {
            for (OrderLine line : order.getLines()) {
                if (line.getProductId() != null) {
                    names.putIfAbsent(line.getProductId(), line.getDescription());
                }
            }
        }
        String detail = shortages.stream().map(s -> names.getOrDefault(s.productId(), "Sản phẩm #" + s.productId())
                + " (cần " + s.requested() + ", còn " + s.available() + ")").collect(Collectors.joining(", "));
        return "Không đủ tồn kho cho: " + detail + ". Xem trước phiếu thu để biết dòng cần xử lý";
    }

    private void auditPaid(SalesOrder order, Payment payment) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.PAID.name());
        after.put("paymentId", payment.getId());
        after.put("paymentCode", payment.getCode());
        after.put("method", payment.getMethod().name());
        after.put("amount", order.getTotalAmount());
        audit.record(AuditEntry.of(SalesAuditActions.ORDER_PAID).entity("orders", order.getId())
                .before(Map.of("status", OrderStatus.PENDING.name())).after(after));
    }
}
