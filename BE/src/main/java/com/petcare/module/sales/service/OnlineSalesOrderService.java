package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link OnlineSalesOrderApi} thật (task 117): Order ONLINE sinh thẳng ở {@code PENDING} (Order#11). Chỉ INSERT một Order
 * mới — không khóa dòng nào, nên thứ tự khóa của shop (giỏ → sản phẩm → lô kho → số chứng từ) không bị đảo.
 */
@Service
@RequiredArgsConstructor
public class OnlineSalesOrderService implements OnlineSalesOrderApi {

    private final SalesOrderRepository orders;
    private final OrderTransitionHandler fsm;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createPendingOrder(String code, Long branchId, Long customerId, Long accountId,
            List<OnlineOrderLineInput> lines) {
        if (code == null || branchId == null || customerId == null || accountId == null) {
            throw new IllegalArgumentException("Order online thiếu code / chi nhánh / khách / tài khoản");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Order online cần ít nhất một dòng hàng");
        }
        for (OnlineOrderLineInput line : lines) {
            if (line.productId() == null || line.name() == null || line.name().isBlank()) {
                throw new IllegalArgumentException("Dòng hàng online thiếu productId / tên");
            }
            if (line.quantity() <= 0 || line.unitPrice() < 0) {
                throw new IllegalArgumentException("Dòng hàng online sai số lượng / đơn giá: sản phẩm " + line.productId());
            }
        }
        fsm.validateInitial(OrderStatus.PENDING);
        Instant now = Instant.now(clock);
        SalesOrder order = SalesOrder.openOnline(code, branchId, customerId, accountId, now);
        for (OnlineOrderLineInput line : lines) {
            order.addLine(OrderLine.of(OrderLineType.GOODS, null, line.productId(), line.name(), line.quantity(),
                    line.unitPrice(), false, accountId, accountId));
        }
        return orders.saveAndFlush(order).getId();
    }
}
