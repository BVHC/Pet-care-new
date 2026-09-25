package com.petcare.module.order.job;

import com.petcare.module.order.entity.Order;
import com.petcare.module.order.repository.OrderRepository;
import com.petcare.module.order.service.OrderService;
import com.petcare.platform.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RULE-14-04 (ProcessOrderTimeout) — đọc từng dòng rồi gọi {@link OrderService#expireOrder}, MỖI
 * đơn một {@code @Transactional} riêng (trong {@code OrderServiceImpl}, không phải 1 transaction
 * bao trọn vòng lặp ở đây) vì mỗi lần expire cần FSM validate + emit event + gọi cross-module
 * release — không phải 1 phép batch SQL như {@code RefreshTokenCleanupService}. Bọc try/catch
 * từng dòng để 1 đơn lỗi (vd race hiếm với actor khác) không chặn các đơn còn lại trong cùng lượt
 * quét (khác {@code CaregiverExpiryService}, nơi cả batch nằm trong 1 transaction vì không có
 * actor thứ 2 nào cạnh tranh).
 */
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutService.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public int processExpiredOrders() {
        List<Order> expired = orderRepository.findAllByStatusAndReservedUntilBefore(
                OrderStatus.PENDING_PAYMENT, LocalDateTime.now());

        int processed = 0;
        for (Order order : expired) {
            try {
                orderService.expireOrder(order.getId());
                processed++;
            } catch (RuntimeException ex) {
                log.warn("ProcessOrderTimeout: bỏ qua order {} do lỗi, sẽ thử lại lượt sau: {}",
                        order.getId(), ex.getMessage());
            }
        }
        return processed;
    }
}
