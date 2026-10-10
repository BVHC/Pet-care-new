package com.petcare.module.customer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.customer.entity.Customer;

import jakarta.persistence.LockModeType;

/**
 * Hồ sơ khách. {@code account_id} có {@code uq_customers_account_id} nên mỗi tài khoản có tối đa một hồ sơ (BR-KH-01).
 * {@code PESSIMISTIC_WRITE} trên PostgreSQL sinh {@code FOR NO KEY UPDATE}: tuần tự hóa lệnh ghi của cùng một khách,
 * không chặn kiểm FK ({@code FOR KEY SHARE}) khi chèn {@code addresses} (docs/adr/0028).
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query("SELECT c FROM Customer c WHERE c.accountId = :accountId")
    Optional<Customer> findByAccountId(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.accountId = :accountId")
    Optional<Customer> findByAccountIdForUpdate(@Param("accountId") Long accountId);

    /** Chỉ lấy id (không nạp entity vào persistence context), để bước khóa sau đó đọc trạng thái mới nhất dưới khóa. */
    @Query("SELECT c.id FROM Customer c WHERE c.accountId = :accountId")
    Optional<Long> findIdByAccountId(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);
}
