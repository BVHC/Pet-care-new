package com.petcare.module.sales.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.SalesOrder;

import lombok.RequiredArgsConstructor;

/**
 * Phiếu thu + các Order của nó → {@link PaymentResponse} (docs/adr/0064). Tên khách qua <b>một</b> lần
 * {@code findContacts}. Chạy trong transaction của service gọi (OSIV tắt). Khách không tồn tại là dữ liệu hỏng →
 * {@link IllegalStateException}.
 */
@Component
@RequiredArgsConstructor
public class PaymentAssembler {

    private final CustomerQueryApi customers;

    public PaymentResponse toResponse(Payment payment, List<SalesOrder> orders) {
        return new PaymentResponse(payment.getId(), payment.getCode(), payment.getBranchId(), payment.getCustomerId(),
                payment.getCashierShiftId(), payment.getMethod(), payment.getAmount(), payment.getReceivedBy(),
                payment.getPaidAt(), toSummaries(orders));
    }

    /** Dòng tóm tắt Order (sales-v1 {@code OrderSummary}) với tên khách ghép theo lô. */
    public List<OrderSummaryResponse> toSummaries(List<SalesOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> customerIds = orders.stream().map(SalesOrder::getCustomerId).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        for (CustomerContact contact : customers.findContacts(customerIds)) {
            names.put(contact.customerId(), contact.fullName());
        }
        return orders.stream().map(order -> {
            String name = names.get(order.getCustomerId());
            if (name == null) {
                throw new IllegalStateException("Order #" + order.getId() + " trỏ tới khách #"
                        + order.getCustomerId() + " không tồn tại");
            }
            return new OrderSummaryResponse(order.getId(), order.getCode(), order.getSource(), order.getStatus(),
                    order.getCustomerId(), name, order.getBranchId(), order.getTotalAmount(), order.getCreatedAt(),
                    order.getPendingAt(), order.getPaidAt());
        }).toList();
    }
}
