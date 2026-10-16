package com.petcare.module.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.catalog.entity.Product;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * Khóa ghi dòng sản phẩm ({@code FOR NO KEY UPDATE}) <b>trước</b> khi đọc entity: sửa sản phẩm — đặc biệt tắt cờ
     * quản lý hạn dùng (BR-SP-05) — tuần tự với xác nhận phiếu nhập kho, vốn đọc sản phẩm {@code FOR SHARE}
     * (docs/adr/0042 mục 2.2).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    java.util.Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /** Khóa chia sẻ: bên đọc để ghi tiếp (xác nhận phiếu nhập) xếp hàng sau lệnh sửa sản phẩm. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    java.util.Optional<Product> findByIdForShare(@Param("id") Long id);

    boolean existsBySku(String sku);

    boolean existsByVaccineTypeId(Long vaccineTypeId);
}
