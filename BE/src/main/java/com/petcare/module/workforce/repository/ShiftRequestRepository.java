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

    /**
     * Yêu cầu {@code PENDING} của <b>một</b> phân ca ({@code FOR NO KEY UPDATE}): hủy phân ca, ghi vắng và sửa đăng ký
     * tuần đóng nó trong cùng transaction (Yêu cầu#5). Tối đa một dòng nhờ {@code ux_shift_requests_pending} (V36);
     * status là literal để dùng partial index đó. Khóa sau {@code accounts} và phân ca (ADR-0123 mục 8).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM ShiftRequest r
            WHERE r.assignmentId = :assignmentId
              AND r.status = com.petcare.module.workforce.entity.ShiftRequestStatus.PENDING
            """)
    Optional<ShiftRequest> findPendingByAssignmentIdForUpdate(@Param("assignmentId") Long assignmentId);

    /** {@code FOR NO KEY UPDATE}: rút, duyệt, từ chối yêu cầu. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ShiftRequest r WHERE r.id = :id")
    Optional<ShiftRequest> findByIdForUpdate(@Param("id") Long id);
}
