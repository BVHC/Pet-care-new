package com.petcare.module.order.job;

import com.petcare.module.order.entity.Order;
import com.petcare.module.order.repository.OrderRepository;
import com.petcare.module.order.service.OrderService;
import com.petcare.platform.enums.OrderChannel;
import com.petcare.platform.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RULE-14-04 (ProcessOrderTimeout). */
@ExtendWith(MockitoExtension.class)
class OrderTimeoutServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderService orderService;

    private OrderTimeoutService service;

    private static Order expiredOrder() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "ORD-20260101-abcd1234",
                OrderChannel.ONLINE_APP, OrderStatus.PENDING_PAYMENT);
        order.setId(UUID.randomUUID());
        order.setReservedUntil(LocalDateTime.now().minusMinutes(1));
        return order;
    }

    @Test
    void processExpiredOrders_callsExpireOrderForEachFound() {
        service = new OrderTimeoutService(orderRepository, orderService);
        Order first = expiredOrder();
        Order second = expiredOrder();
        when(orderRepository.findAllByStatusAndReservedUntilBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));

        int processed = service.processExpiredOrders();

        assertThat(processed).isEqualTo(2);
        verify(orderService).expireOrder(first.getId());
        verify(orderService).expireOrder(second.getId());
    }

    @Test
    void processExpiredOrders_oneFails_othersStillProcessed() {
        service = new OrderTimeoutService(orderRepository, orderService);
        Order first = expiredOrder();
        Order second = expiredOrder();
        when(orderRepository.findAllByStatusAndReservedUntilBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        doThrow(new RuntimeException("race")).when(orderService).expireOrder(first.getId());

        int processed = service.processExpiredOrders();

        assertThat(processed).isEqualTo(1);
        verify(orderService, times(1)).expireOrder(second.getId());
    }

    @Test
    void processExpiredOrders_none_returnsZero() {
        service = new OrderTimeoutService(orderRepository, orderService);
        when(orderRepository.findAllByStatusAndReservedUntilBefore(any(OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.processExpiredOrders()).isZero();
    }
}
