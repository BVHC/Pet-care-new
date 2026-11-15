package com.petcare.module.shop.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code carts} (ROOT, V44): giỏ hàng của một khách, tạo lười ở lần ghi đầu tiên bằng
 * {@code INSERT … ON CONFLICT DO NOTHING} ({@link com.petcare.module.shop.repository.CartRepository#insertIfAbsent}),
 * nên entity chỉ để đọc (BR-BH-07, docs/adr/0141).
 */
@Getter
@Entity
@Table(name = "carts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;
}
