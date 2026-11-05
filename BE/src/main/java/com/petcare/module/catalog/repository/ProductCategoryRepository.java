package com.petcare.module.catalog.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.catalog.entity.ProductCategory;

public interface ProductCategoryRepository
        extends JpaRepository<ProductCategory, Long>, JpaSpecificationExecutor<ProductCategory> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * Khóa dòng (Hibernate phát {@code FOR NO KEY UPDATE}) cho thao tác sửa: đọc rồi ghi không khóa sẽ UPDATE tất cả cột
     * từ bản đã đọc và ghi đè thay đổi chen giữa (docs/adr/0106). Phải gọi trong transaction của use case.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM ProductCategory x WHERE x.id = :id")
    Optional<ProductCategory> findByIdForUpdate(@Param("id") Long id);
}
