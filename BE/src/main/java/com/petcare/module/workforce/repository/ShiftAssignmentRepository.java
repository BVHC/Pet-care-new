package com.petcare.module.workforce.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.workforce.entity.ShiftAssignment;

import jakarta.persistence.LockModeType;

/** Bảng {@code shift_assignments} (docs/adr/0138). Hàm khóa là {@code MANDATORY}, xem {@code ShiftDefinitionRepository}. */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {

    /** {@code FOR NO KEY UPDATE}: hủy, ghi vắng, duyệt đổi / nghỉ (thứ tự khóa ở ADR-0123 mục 8, workforce-v1 §D.5). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ShiftAssignment a WHERE a.id = :id")
    Optional<ShiftAssignment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Số phân ca {@code SCHEDULED} của ca từ ngày {@code fromDate} trở đi: guard "ngừng dùng ca" (BR-NS-01). Status là
     * literal để predicate khớp {@code ix_shift_assignments_definition_scheduled} (partial).
     */
    @Query(value = """
            SELECT COUNT(*) FROM shift_assignments
            WHERE shift_definition_id = :definitionId AND status = 'SCHEDULED' AND work_date >= :fromDate
            """, nativeQuery = true)
    long countScheduledFrom(@Param("definitionId") Long definitionId, @Param("fromDate") LocalDate fromDate);

    /**
     * Ca đã có phân ca ở bất kỳ trạng thái nào (kể cả đã qua): guard "sửa giờ ca" (BR-NS-01). V36 không có index chỉ
     * theo {@code shift_definition_id}; thêm {@code branch_id} (luôn bằng chi nhánh của ca) để đi qua
     * {@code ix_shift_assignments_branch_date} thay vì quét cả bảng.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM shift_assignments
                           WHERE branch_id = :branchId AND shift_definition_id = :definitionId)
            """, nativeQuery = true)
    boolean existsAnyForDefinition(@Param("branchId") Long branchId, @Param("definitionId") Long definitionId);
}
