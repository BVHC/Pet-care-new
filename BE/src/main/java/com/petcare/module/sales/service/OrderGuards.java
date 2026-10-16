package com.petcare.module.sales.service;

import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.platform.exception.InvalidStateTransitionException;

/** Guard dùng chung của {@code VisitOrderService} và {@code OrderLineService} (cùng một thông báo 409). */
final class OrderGuards {

    private OrderGuards() {
    }

    /** Thêm / sửa / xóa dòng của người tạo dòng chỉ khi Order còn {@code OPEN} (03 #5, BR-BH-02). */
    static void requireOpen(SalesOrder order) {
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new InvalidStateTransitionException("đơn hàng (chỉ thực hiện được khi đang mở)",
                    order.getStatus().name(), OrderStatus.OPEN.name());
        }
    }
}
