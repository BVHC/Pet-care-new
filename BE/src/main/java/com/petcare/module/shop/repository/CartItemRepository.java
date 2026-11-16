package com.petcare.module.shop.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.shop.entity.CartItem;

/** Bảng {@code cart_items}; mọi lệnh ghi chạy dưới khóa dòng {@code carts} của khách (docs/adr/0143). */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** Các dòng của giỏ theo thứ tự thêm vào. */
    List<CartItem> findByCartIdOrderById(Long cartId);

    /**
     * Đặt số lượng bằng một upsert tường minh: ghi cả khi số lượng không đổi (kiểm bẩn của JPA sẽ bỏ qua) và đặt
     * {@code updated_at} (lệnh native không qua listener).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO cart_items (cart_id, product_id, quantity, created_at, updated_at)
            VALUES (:cartId, :productId, :quantity, :now, :now)
            ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity, updated_at = :now""",
            nativeQuery = true)
    int upsert(@Param("cartId") Long cartId, @Param("productId") Long productId, @Param("quantity") int quantity,
            @Param("now") Instant now);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartItem i WHERE i.cartId = :cartId AND i.productId IN :productIds")
    int deleteByCartIdAndProductIdIn(@Param("cartId") Long cartId, @Param("productIds") Collection<Long> productIds);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartItem i WHERE i.cartId = :cartId")
    int deleteByCartId(@Param("cartId") Long cartId);
}
