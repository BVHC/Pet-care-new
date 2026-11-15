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
 * Bảng {@code cart_items} (PART, V44): một dòng giỏ, duy nhất theo (giỏ, sản phẩm). Ghi bằng upsert / xóa hàng loạt của
 * {@link com.petcare.module.shop.repository.CartItemRepository} (không qua kiểm bẩn của JPA, docs/adr/0141), entity chỉ để đọc.
 */
@Getter
@Entity
@Table(name = "cart_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private short quantity;
}
