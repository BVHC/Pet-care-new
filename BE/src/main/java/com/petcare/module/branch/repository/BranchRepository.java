package com.petcare.module.branch.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.branch.entity.Branch;

import jakarta.persistence.LockModeType;

public interface BranchRepository extends JpaRepository<Branch, Long>, JpaSpecificationExecutor<Branch> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * Khóa dòng chi nhánh cho các thao tác ghi của branch (kích hoạt, đặt giờ mở cửa, thêm ngày nghỉ) để hai thao
     * tác cùng chi nhánh không chạy chen nhau. Hibernate phát {@code FOR NO KEY UPDATE} (ADR-0022).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Branch b WHERE b.id = :id")
    Optional<Branch> findByIdForUpdate(@Param("id") Long id);

    /**
     * Khóa dòng chi nhánh cho luồng quản trị nhân viên của identity ({@code BranchApi}, docs/adr/0029, nợ D014) và trả
     * {@code status} vừa đọc dưới khóa. Native, trả giá trị vô hướng: không nạp entity, nên không bao giờ trả lại một
     * {@code Branch} cũ đã nằm sẵn trong persistence context (ví dụ do {@code BranchQueryApi.findBranch} nạp trước
     * trong cùng transaction) và commit không thể sinh {@code UPDATE branches}. Cùng chế độ khóa với
     * {@link #findByIdForUpdate} ({@code FOR NO KEY UPDATE}).
     */
    @Query(value = "SELECT status FROM branches WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<String> lockStatusForStaffChange(@Param("id") Long id);
}
