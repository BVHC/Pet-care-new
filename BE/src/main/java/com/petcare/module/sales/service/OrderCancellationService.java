package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderReasonRequest;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Hủy Order khách không thanh toán (sales-v1 #10, UC66, Order#10; BR-BH-05, BR-BC-02, BR-QT-15; docs/adr/0051). Chỉ
 * BRANCH_MANAGER của chi nhánh Order (vai trò kiểm ở controller, chi nhánh ở đây). Một {@code @Transactional}, không
 * {@code noRollbackFor}: khóa dòng Order (404) → chi nhánh 403 → không {@code PENDING} 409 → thiếu lý do 400 → Order
 * BOARDING mà thú còn trong chuồng 400 → FSM → ghi → audit. Không đổi kho, bệnh án, lịch hẹn, lưu trú; không phát sự
 * kiện. Báo cáo thất thu đọc {@code cancel_type = UNPAID}.
 * <p>
 * Đặt chỗ đọc không khóa mà vẫn đúng: thú còn trong chuồng ({@code CHECKED_IN} / {@code OVERDUE}) chỉ rời chuồng bằng
 * cách thu chính Order này (phải chờ khóa Order đang giữ) hoặc kết thúc vì thú mất (trạng thái cuối), nên kết quả đọc
 * không thể đảo chiều trước khi transaction này commit.
 */
@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private static final Set<StayStatus> PET_IN_KENNEL = Set.of(StayStatus.CHECKED_IN, StayStatus.OVERDUE);

    private final SalesOrderRepository orders;
    private final BoardingQueryApi boarding;
    private final OrderTransitionHandler fsm;
    private final OrderAssembler assembler;
    private final AuditRecorder audit;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional
    public OrderResponse cancelUnpaidOrder(Long orderId, OrderReasonRequest request) {
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(order.getBranchId());
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidStateTransitionException("đơn hàng (chỉ hủy được Order đang chờ thu tiền)",
                    order.getStatus().name(), OrderStatus.CANCELLED.name());
        }
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) {
            throw new BusinessRuleViolationException("BR-BH-05", "Vui lòng nhập lý do hủy Order");
        }
        if (order.getSource() == OrderSource.BOARDING) {
            requirePetLeftKennel(order);
        }
        fsm.validateTransition(order.getStatus(), OrderStatus.CANCELLED);

        SecurityPrincipal me = branchScope.current();
        order.cancel(OrderCancelType.UNPAID, me.accountId(), reason, Instant.now(clock));
        orders.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.CANCELLED.name());
        after.put("cancelType", OrderCancelType.UNPAID.name());
        after.put("totalAmount", order.getTotalAmount());
        audit.record(AuditEntry.of(SalesAuditActions.ORDER_CANCELLED_UNPAID).entity("orders", order.getId())
                .before(Map.of("status", OrderStatus.PENDING.name()))
                .after(after)
                .reason(reason));
        return assembler.assemble(order, me);
    }

    /**
     * Hủy phiên trả thú (sales-v1 #9, Order#9; BR-LT-09, BR-QT-15; docs/adr/0086). Chỉ lễ tân của chi nhánh Order (vai
     * trò ở controller). Thứ tự như {@link #cancelUnpaidOrder}: khóa Order (404) → chi nhánh 403 → không phải Order
     * BOARDING {@code PENDING} 409 → thiếu lý do 400 → thú đã rời chuồng 400 → FSM → ghi → audit. Không tính thất thu
     * ({@code CHECKOUT_ABORTED}); đặt chỗ giữ trạng thái và tiếp tục tính đêm, lần trả sau sinh Order mới.
     */
    @Transactional
    public OrderResponse abortCheckout(Long orderId, OrderReasonRequest request) {
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(order.getBranchId());
        if (order.getStatus() != OrderStatus.PENDING || order.getSource() != OrderSource.BOARDING) {
            throw new InvalidStateTransitionException("đơn hàng (chỉ hủy phiên trả thú của Order lưu trú đang chờ thu)",
                    order.getStatus().name(), OrderStatus.CANCELLED.name());
        }
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-09", "Vui lòng nhập lý do hủy phiên trả thú");
        }
        StayStatus stay = boarding.findBooking(order.getBoardingBookingId())
                .orElseThrow(() -> new IllegalStateException("Order #" + order.getId() + " trỏ tới đặt chỗ #"
                        + order.getBoardingBookingId() + " không tồn tại"))
                .status();
        if (!PET_IN_KENNEL.contains(stay)) {
            throw new BusinessRuleViolationException("BR-LT-09",
                    "Thú không còn trong chuồng: dùng hủy Order khách không thanh toán (BR-BH-05)");
        }
        fsm.validateTransition(order.getStatus(), OrderStatus.CANCELLED);

        SecurityPrincipal me = branchScope.current();
        order.cancel(OrderCancelType.CHECKOUT_ABORTED, me.accountId(), reason, Instant.now(clock));
        orders.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", OrderStatus.CANCELLED.name());
        after.put("cancelType", OrderCancelType.CHECKOUT_ABORTED.name());
        after.put("totalAmount", order.getTotalAmount());
        audit.record(AuditEntry.of(SalesAuditActions.ORDER_CHECKOUT_ABORTED).entity("orders", order.getId())
                .before(Map.of("status", OrderStatus.PENDING.name()))
                .after(after)
                .reason(reason));
        return assembler.assemble(order, me);
    }

    /** BR-BH-05: không áp dụng cho Order lưu trú khi thú còn trong chuồng — khi đó lễ tân hủy phiên trả thú (BR-LT-09). */
    private void requirePetLeftKennel(SalesOrder order) {
        StayStatus status = boarding.findBooking(order.getBoardingBookingId())
                .orElseThrow(() -> new IllegalStateException("Order #" + order.getId() + " trỏ tới đặt chỗ #"
                        + order.getBoardingBookingId() + " không tồn tại"))
                .status();
        if (PET_IN_KENNEL.contains(status)) {
            throw new BusinessRuleViolationException("BR-BH-05",
                    "Thú còn trong chuồng: hủy phiên trả thú thay vì hủy Order khách không thanh toán (BR-LT-09)");
        }
    }
}
