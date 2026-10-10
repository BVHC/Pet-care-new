package com.petcare.module.customer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.customer.entity.Address;

/**
 * Sổ địa chỉ (BR-TK-18, docs/adr/0028). Mọi câu lọc theo chủ ({@code customer_id}) ngay trong SQL; câu đọc theo chủ dùng
 * {@code ix_addresses_customer_id} (V10). Viết native để IT {@code EXPLAIN} được đúng chuỗi SQL đang chạy.
 */
public interface AddressRepository extends JpaRepository<Address, Long> {

    /** Mặc định trước, rồi theo thứ tự thêm (customer-v1 A7). */
    @Query(value = """
            SELECT * FROM addresses WHERE customer_id = :customerId ORDER BY is_default DESC, id
            """, nativeQuery = true)
    List<Address> findOwnedBy(@Param("customerId") Long customerId);

    /** Địa chỉ của khác chủ cũng là "không có" → 404 cùng message (customer-v1 A8). */
    @Query(value = "SELECT * FROM addresses WHERE id = :id AND customer_id = :customerId", nativeQuery = true)
    Optional<Address> findOwned(@Param("id") Long id, @Param("customerId") Long customerId);

    @Query(value = "SELECT count(*) FROM addresses WHERE customer_id = :customerId", nativeQuery = true)
    long countOwnedBy(@Param("customerId") Long customerId);

    /**
     * Gỡ cờ của địa chỉ mặc định hiện tại, chạy ngay (không chờ flush): Hibernate flush INSERT trước UPDATE nên đổi cờ
     * bằng setter sẽ vấp {@code uq_addresses_default_per_customer} (không deferrable). {@code flushAutomatically} ghi
     * thay đổi đang chờ trước; không {@code clearAutomatically} để entity {@code Customer} đang khóa không bị detach.
     * Câu bulk bỏ qua auditing listener nên tự đặt {@code updated_at} (docs/adr/0015).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE addresses SET is_default = false, updated_at = :now
            WHERE customer_id = :customerId AND is_default
            """, nativeQuery = true)
    int clearDefault(@Param("customerId") Long customerId, @Param("now") Instant now);
}
