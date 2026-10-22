package com.petcare.module.inventory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.inventory.entity.Supplier;

import jakarta.persistence.LockModeType;

/**
 * Nhà cung cấp (BR-KO-02, docs/adr/0042). {@code PESSIMISTIC_READ} sinh {@code FOR SHARE}: phiếu nhập đọc nhà cung cấp
 * dưới khóa chia sẻ nên sửa / ngừng hợp tác xếp hàng sau. Xóa dùng {@code FOR UPDATE} (native) vì chỉ khóa này mới xung
 * đột với {@code FOR KEY SHARE} mà FK của một phiếu nhập đang chèn giữ.
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Supplier s WHERE s.id = :id")
    Optional<Supplier> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Supplier s WHERE s.id = :id")
    Optional<Supplier> findByIdForShare(@Param("id") Long id);

    /** Khóa xóa: chặn mọi phiếu nhập đang chèn trỏ tới nhà cung cấp này. */
    @Query(value = "SELECT id FROM suppliers WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockForDelete(@Param("id") Long id);

    /** Bất kỳ phiếu nào (nháp, đã xác nhận, đã hủy) — BR-KO-02. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM stock_receipts WHERE supplier_id = :id)", nativeQuery = true)
    boolean hasReceipts(@Param("id") Long id);
}
