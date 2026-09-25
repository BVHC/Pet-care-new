package com.petcare.module.order.repository;

import com.petcare.module.order.entity.Order;
import com.petcare.platform.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findAllByCustomerId(UUID customerId, Pageable pageable);

    /** ProcessOrderTimeoutJob — đơn Online quá hạn giữ chỗ 15 phút chưa thanh toán (RULE-14-04). */
    List<Order> findAllByStatusAndReservedUntilBefore(OrderStatus status, LocalDateTime cutoff);
}
