package com.petcare.module.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.inventory.entity.StockAdjustment;

/** Phiếu điều chỉnh tồn (BR-KO-06); danh sách lọc theo chi nhánh và khoảng ngày bằng {@code Specification}. */
public interface StockAdjustmentRepository
        extends JpaRepository<StockAdjustment, Long>, JpaSpecificationExecutor<StockAdjustment> {
}
