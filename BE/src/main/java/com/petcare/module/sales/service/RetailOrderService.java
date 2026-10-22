package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.CreateRetailOrderRequest;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Order bán lẻ tại quầy (sales-v1 #1, #7, #8; UC66; Order#2, #5, #7; BR-BH-01; docs/adr/0051). Lễ tân của chi nhánh
 * (vai trò kiểm ở controller); mọi lễ tân của chi nhánh chốt / hủy được, không riêng người tạo. Thêm / xóa dòng dùng
 * {@link OrderLineService} như mọi nguồn khác (cảnh báo thiếu tồn, chặn thuốc kê đơn).
 * <p>
 * Mỗi use case một {@code @Transactional}, không {@code noRollbackFor}. Chốt / hủy <b>khóa dòng Order trước khi đọc</b>
 * (cùng khóa với thêm / xóa dòng, nên "chốt ∥ xóa dòng cuối" và "chốt ∥ hủy" chạy tuần tự); thứ tự kiểm: 404 → 403 chi
 * nhánh → 409 nguồn / trạng thái → 400 rule → FSM.
 */
@Service
@RequiredArgsConstructor
public class RetailOrderService {

    private final SalesOrderRepository orders;
    private final CustomerQueryApi customers;
    private final DocumentCodeGenerator codes;
    private final OrderTransitionHandler fsm;
    private final OrderAssembler assembler;
    private final BranchScope branchScope;
    private final Clock clock;

    /**
     * Order#2. Chi nhánh = chi nhánh của lễ tân. Khách không tồn tại → 404. Không khóa {@code customers}: khóa FK
     * {@code FOR KEY SHARE} của câu INSERT đủ chặn việc xóa hồ sơ online song song (UC07 / ST02 nhận lỗi FK). Mã sinh
     * sau cùng để giữ khóa {@code document_counters} ngắn nhất (ADR-0043).
     */
    @Transactional
    public OrderResponse createRetailOrder(CreateRetailOrderRequest request) {
        SecurityPrincipal me = branchScope.current();
        Long branchId = me.branchId();
        if (branchId == null) {
            throw new IllegalStateException("Lễ tân #" + me.accountId() + " không có chi nhánh");
        }
        if (customers.findContact(request.customerId()).isEmpty()) {
            throw new ResourceNotFoundException("khách hàng", request.customerId());
        }
        fsm.validateInitial(OrderStatus.OPEN);
        String code = codes.next(VisitOrderService.DOC_TYPE, VisitOrderService.PREFIX, LocalDate.now(clock));
        SalesOrder order = orders.saveAndFlush(SalesOrder.openRetail(code, branchId, request.customerId(),
                me.accountId()));
        return assembler.assemble(order, me);
    }

    /** Order#5: chốt Order bán lẻ {@code OPEN} có ≥ 1 dòng → {@code PENDING} (chờ thu, chưa giao hàng — BR-BH-06). */
    @Transactional
    public OrderResponse closeRetailOrder(Long orderId) {
        SalesOrder order = lockOpenRetail(orderId, OrderStatus.PENDING);
        if (order.getLines().isEmpty()) {
            throw new BusinessRuleViolationException("BR-BH-01", "Order chưa có dòng nào, không chốt được");
        }
        fsm.validateTransition(order.getStatus(), OrderStatus.PENDING);
        order.markPending(Instant.now(clock));
        orders.flush();
        return assembler.assemble(order, branchScope.current());
    }

    /** Order#7: hủy Order bán lẻ chưa chốt; không cần lý do, các dòng giữ nguyên làm lịch sử, kho không đổi. */
    @Transactional
    public OrderResponse cancelRetailOrder(Long orderId) {
        SalesOrder order = lockOpenRetail(orderId, OrderStatus.CANCELLED);
        fsm.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
        SecurityPrincipal me = branchScope.current();
        order.cancel(OrderCancelType.RETAIL_UNCLOSED, me.accountId(), null, Instant.now(clock));
        orders.flush();
        return assembler.assemble(order, me);
    }

    /** Khóa dòng Order (404), kiểm chi nhánh (403), rồi phải là Order bán lẻ đang {@code OPEN} (409). */
    private SalesOrder lockOpenRetail(Long orderId, OrderStatus target) {
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(order.getBranchId());
        if (order.getSource() != OrderSource.RETAIL || order.getStatus() != OrderStatus.OPEN) {
            throw new InvalidStateTransitionException("đơn hàng (chỉ Order bán lẻ đang mở)",
                    order.getStatus().name(), target.name());
        }
        return order;
    }
}
