package com.petcare.module.customer.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
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

    /**
     * Hồ sơ theo lô ({@code CustomerQueryApi.findContacts}): một câu SQL, JPQL constructor nên không nạp entity vào
     * persistence context của bên gọi. Bên gọi không truyền tập rỗng.
     */
    @Query("""
            SELECT new com.petcare.module.customer.api.CustomerQueryApi$CustomerContact(
                c.id, c.fullName, c.phone, c.email, c.accountId, c.linkDecisionPending)
            FROM Customer c WHERE c.id IN :ids
            """)
    List<CustomerContact> findContactsByIds(@Param("ids") Collection<Long> ids);

    /** BR-TK-19: có ít nhất một hồ sơ tại quầy chưa liên kết cùng SĐT (dùng {@code ix_customers_phone}). */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM customers
                           WHERE created_channel = 'COUNTER' AND account_id IS NULL AND phone = :phone)
            """, nativeQuery = true)
    boolean existsUnlinkedCounterProfileWithPhone(@Param("phone") String phone);

    /** UC07: ứng viên liên kết của SĐT, theo id tăng dần. */
    @Query(value = """
            SELECT * FROM customers
            WHERE created_channel = 'COUNTER' AND account_id IS NULL AND phone = :phone ORDER BY id
            """, nativeQuery = true)
    List<Customer> findUnlinkedCounterProfilesByPhone(@Param("phone") String phone);

    /** BR-KH-10: hồ sơ khác (mọi nguồn) cùng SĐT, theo id, tối đa {@code limit} dòng. */
    @Query(value = "SELECT * FROM customers WHERE phone = :phone AND id <> :excludeId ORDER BY id LIMIT :limit",
            nativeQuery = true)
    List<Customer> findOtherProfilesByPhone(@Param("phone") String phone, @Param("excludeId") Long excludeId,
            @Param("limit") int limit);
}
