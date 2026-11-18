package com.petcare.module.workforce.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.workforce.entity.ShiftDefinition;

import jakarta.persistence.LockModeType;

/**
 * Bảng {@code shift_definitions} (docs/adr/0138). Hàm khóa là {@code MANDATORY}: bên gọi mở transaction, nếu không khóa
 * nhả ngay mà không báo lỗi (khuôn {@code CareTaskRepository.findByIdForUpdate}).
 */
public interface ShiftDefinitionRepository extends JpaRepository<ShiftDefinition, Long> {

    /** {@code FOR NO KEY UPDATE} (Hibernate 6): sửa / ngừng dùng ca, đọc ca dưới khóa rồi mới kiểm guard. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ShiftDefinition d WHERE d.id = :id")
    Optional<ShiftDefinition> findByIdForUpdate(@Param("id") Long id);

    /**
     * Chi nhánh của ca, đọc không khóa và không nạp entity để phân quyền trước khi khóa (khuôn
     * {@code KennelRepository.findBranchIdById}); entity chỉ được nạp một lần, dưới khóa (docs/adr/0138).
     */
    @Query(value = "SELECT branch_id FROM shift_definitions WHERE id = :id", nativeQuery = true)
    Optional<Long> findBranchIdById(@Param("id") Long id);

    /**
     * Tên đã có trong chi nhánh, không phân biệt hoa thường (BR-NS-01), cùng biểu thức với unique index
     * {@code ux_shift_definitions_branch_name (branch_id, lower(name))}. {@code excludeId} loại chính ca đang sửa
     * (null khi tạo); {@code CAST} để PostgreSQL suy được kiểu khi tham số là null.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM shift_definitions
                           WHERE branch_id = :branchId AND lower(name) = lower(:name)
                             AND id <> COALESCE(CAST(:excludeId AS bigint), 0))
            """, nativeQuery = true)
    boolean existsByBranchAndName(@Param("branchId") Long branchId, @Param("name") String name,
            @Param("excludeId") Long excludeId);

    /** Ca của chi nhánh, sắp theo giờ bắt đầu tăng dần (trùng thì {@code id}); không phân trang (workforce-v1 #1). */
    @Query("""
            SELECT d FROM ShiftDefinition d
            WHERE d.branchId = :branchId AND (:activeOnly = false OR d.active = true)
            ORDER BY d.startTime ASC, d.id ASC
            """)
    List<ShiftDefinition> findByBranch(@Param("branchId") Long branchId, @Param("activeOnly") boolean activeOnly);
}
