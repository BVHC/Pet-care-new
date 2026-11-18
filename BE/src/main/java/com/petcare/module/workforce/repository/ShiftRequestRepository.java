package com.petcare.module.workforce.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.workforce.entity.ShiftRequest;

import jakarta.persistence.LockModeType;

/** Bảng {@code shift_requests} (docs/adr/0138). Hàm khóa là {@code MANDATORY}, xem {@code ShiftDefinitionRepository}. */
public interface ShiftRequestRepository extends JpaRepository<ShiftRequest, Long> {

    /** Yêu cầu {@code PENDING} của một phân ca (mỗi phân ca tối đa một, BR-NS-04). */
    interface PendingRequestRow {
        Long getAssignmentId();

        Long getRequestId();
    }

    /**
     * Yêu cầu {@code PENDING} của các phân ca trong danh sách, một câu cho cả trang (workforce-v1 {@code pendingRequestId}).
     * Status là literal để dùng partial {@code ux_shift_requests_pending}. Bên gọi không truyền tập rỗng.
     */
    @Query(value = """
            SELECT assignment_id AS assignmentId, id AS requestId
            FROM shift_requests
            WHERE assignment_id IN (:assignmentIds) AND status = 'PENDING'
            """, nativeQuery = true)
    List<PendingRequestRow> findPendingByAssignmentIds(@Param("assignmentIds") Collection<Long> assignmentIds);

    /** {@code FOR NO KEY UPDATE}: rút, duyệt, từ chối yêu cầu. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ShiftRequest r WHERE r.id = :id")
    Optional<ShiftRequest> findByIdForUpdate(@Param("id") Long id);
}
