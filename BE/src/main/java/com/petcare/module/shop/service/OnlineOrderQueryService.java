package com.petcare.module.shop.service;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.shop.api.OnlineOrderQueryApi;
import com.petcare.module.shop.repository.OnlineOrderRepository;

/** {@link OnlineOrderQueryApi} thật (task 125, docs/adr/0145): đọc {@code online_orders.delivered_at} theo {@code order_id}. */
@Service
public class OnlineOrderQueryService implements OnlineOrderQueryApi {

    private final OnlineOrderRepository onlineOrders;

    public OnlineOrderQueryService(OnlineOrderRepository onlineOrders) {
        this.onlineOrders = onlineOrders;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findDeliveredAtByOrderId(Long orderId) {
        return onlineOrders.findDeliveredAtByOrderId(orderId);
    }
}
