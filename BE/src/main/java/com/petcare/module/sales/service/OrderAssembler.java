package com.petcare.module.sales.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.sales.dto.OrderLineResponse;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.mapper.OrderMapper;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Dựng {@link OrderResponse} cho người đang xem: tên khách từ {@code CustomerQueryApi}, cờ {@code canEdit} /
 * {@code canDelete} từ {@link OrderLinePolicy} (cùng nguồn với guard nên không lệch hành vi thật). Chạy trong
 * transaction của service gọi (OSIV tắt, ADR-0020).
 */
@Component
@RequiredArgsConstructor
class OrderAssembler {

    private final CustomerQueryApi customers;
    private final OrderMapper mapper;

    OrderResponse assemble(SalesOrder order, SecurityPrincipal viewer) {
        String customerName = customers.findContact(order.getCustomerId())
                .map(CustomerQueryApi.CustomerContact::fullName)
                .orElseThrow(() -> new IllegalStateException("Order #" + order.getId() + " trỏ tới khách #"
                        + order.getCustomerId() + " không tồn tại"));
        List<OrderLineResponse> lines = order.getLines().stream()
                .map(line -> toLine(order, line, viewer))
                .toList();
        return mapper.toResponse(order, customerName, lines);
    }

    private OrderLineResponse toLine(SalesOrder order, OrderLine line, SecurityPrincipal viewer) {
        return mapper.toLine(line,
                OrderLinePolicy.canEdit(viewer.role(), viewer.accountId(), order, line),
                OrderLinePolicy.canDelete(viewer.role(), viewer.accountId(), order, line));
    }
}
