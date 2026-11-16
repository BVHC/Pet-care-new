package com.petcare.module.shop.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.shop.entity.OnlineOrder;

/** Bảng {@code online_orders} (docs/adr/0144). */
public interface OnlineOrderRepository extends JpaRepository<OnlineOrder, Long> {
}
