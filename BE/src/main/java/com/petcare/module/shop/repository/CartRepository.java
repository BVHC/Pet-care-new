package com.petcare.module.shop.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.shop.entity.Cart;

/** Bảng {@code carts} (docs/adr/0143). */
public interface CartRepository extends JpaRepository<Cart, Long> {

    /** Giỏ của khách, đọc không khóa (GET). */
    @Query(value = "SELECT id FROM carts WHERE customer_id = :customerId", nativeQuery = true)
    Optional<Long> findIdByCustomerId(@Param("customerId") Long customerId);

    /**
     * Tạo giỏ nếu khách chưa có; hai lần ghi đồng thời cùng tạo thì lần sau không làm gì và chờ lần trước commit.
     * {@code created_at} / {@code updated_at} đặt tường minh từ {@code Clock} (INSERT native không qua listener).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO carts (customer_id, created_at, updated_at) VALUES (:customerId, :now, :now)
            ON CONFLICT (customer_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("customerId") Long customerId, @Param("now") Instant now);

    /**
     * Khóa dòng giỏ ({@code FOR NO KEY UPDATE}) và trả id; đọc bằng truy vấn scalar nên không có entity cũ trong persistence
     * context. {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM carts WHERE customer_id = :customerId FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Long> lockIdByCustomerId(@Param("customerId") Long customerId);
}
