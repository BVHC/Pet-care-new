package com.petcare.module.sales.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Xem một Order (sales-v1 #3, UC66, UC67, UC70; docs/adr/0044). Nhân viên chi nhánh của Order: Order chi nhánh khác →
 * 403 ({@code BranchScope.check}), không có → 404. Đọc thuần ở READ COMMITTED, không khóa.
 */
@Service
@RequiredArgsConstructor
public class OrderViewService {

    private final SalesOrderRepository orders;
    private final OrderAssembler assembler;
    private final BranchScope branchScope;

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        SalesOrder order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(order.getBranchId());
        return assembler.assemble(order, branchScope.current());
    }
}
