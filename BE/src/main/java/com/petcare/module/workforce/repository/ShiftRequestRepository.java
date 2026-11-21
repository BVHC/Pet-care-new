package com.petcare.module.workforce.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;

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

    /**
     * Yêu cầu#5 hàng loạt (BR-NS-09, nhân viên rời chi nhánh): yêu cầu {@code PENDING} của các phân ca vừa bị hủy →
     * {@code REJECTED}, hệ thống ghi chú, {@code decided_by} giữ NULL. Ghi đủ cột như {@code ShiftRequest.rejectBySystem}
     * (CHECK V36 {@code ck_shift_requests_closed_at} / {@code _rejected_note}); {@code status = 'PENDING'} nằm trong câu
     * nên yêu cầu vừa được xử lý trong lúc chờ khóa không bị ghi đè (READ COMMITTED kiểm lại điều kiện). Literal để dùng
     * partial {@code ux_shift_requests_pending}. Khuôn {@code CareTaskRepository.cancelOpenVaccineTasks}, ADR-0138 mục 7.
     * Bên gọi không truyền tập rỗng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE shift_requests SET status = 'REJECTED', decision_note = :note, closed_at = :now, updated_at = :now
            WHERE assignment_id IN (:assignmentIds) AND status = 'PENDING'
            """, nativeQuery = true)
    int rejectPendingBySystem(@Param("assignmentIds") Collection<Long> assignmentIds, @Param("note") String note,
            @Param("now") Instant now);

    /** {@code FOR NO KEY UPDATE}: rút, duyệt, từ chối yêu cầu. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ShiftRequest r WHERE r.id = :id")
    Optional<ShiftRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * Người gửi, phân ca và ca đích của một yêu cầu, đọc <b>không khóa</b> để phân quyền dữ liệu trước khi khóa (rút #11,
     * từ chối #14, duyệt #13). Duyệt {@code SWAP} dùng ca đích để khóa ca đó {@code FOR SHARE} trước phân ca (ADR-0139
     * mục 18); {@code null} với {@code LEAVE}.
     */
    interface RequestOwner {
        Long getRequesterId();

        Long getAssignmentId();

        Long getTargetShiftDefinitionId();
    }

    /**
     * Chủ, phân ca và ca đích của yêu cầu để kiểm 404 trước mọi khóa; ba cột này {@code updatable = false} nên đọc không
     * khóa không bị lỗi thời. Rỗng = không có yêu cầu.
     */
    @Query("""
            SELECT r.requesterId AS requesterId, r.assignmentId AS assignmentId,
                   r.targetShiftDefinitionId AS targetShiftDefinitionId
            FROM ShiftRequest r WHERE r.id = :id
            """)
    Optional<RequestOwner> findOwnerById(@Param("id") Long id);

    /**
     * Yêu cầu của <b>một</b> nhân viên (workforce-v1 #10), mới nhất trước: {@code createdAt}, {@code id} giảm dần.
     * {@code status} tùy chọn ({@code null} = không lọc). Điều kiện cố định {@code requester_id} đi qua
     * {@code ix_shift_requests_requester_created}. Phải truyền {@code Pageable} chưa sắp (thứ tự nằm trong câu).
     */
    @Query(value = """
            SELECT r FROM ShiftRequest r
            WHERE r.requesterId = :requesterId AND (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC, r.id DESC
            """, countQuery = """
            SELECT count(r) FROM ShiftRequest r
            WHERE r.requesterId = :requesterId AND (:status IS NULL OR r.status = :status)
            """)
    Page<ShiftRequest> findByRequester(@Param("requesterId") Long requesterId,
            @Param("status") ShiftRequestStatus status, Pageable pageable);

    /**
     * Yêu cầu của <b>một chi nhánh</b> (workforce-v1 #12), cũ nhất trước: {@code createdAt}, {@code id} tăng dần để quản
     * lý xử lý theo thứ tự gửi. Chi nhánh là chi nhánh của <b>phân ca</b> (không phải chi nhánh hiện tại của người gửi:
     * yêu cầu của người đã điều chuyển vẫn thuộc chi nhánh cũ), nối {@code shift_assignments} theo khóa chính.
     * {@code status} bắt buộc (bên gọi thay "vắng" bằng {@code PENDING}) để đi qua
     * {@code ix_shift_requests_status_created} (V36). Phải truyền {@code Pageable} chưa sắp (thứ tự nằm trong câu).
     */
    @Query(value = """
            SELECT r FROM ShiftRequest r JOIN ShiftAssignment a ON a.id = r.assignmentId
            WHERE a.branchId = :branchId AND r.status = :status
            ORDER BY r.createdAt ASC, r.id ASC
            """, countQuery = """
            SELECT count(r) FROM ShiftRequest r JOIN ShiftAssignment a ON a.id = r.assignmentId
            WHERE a.branchId = :branchId AND r.status = :status
            """)
    Page<ShiftRequest> findByBranch(@Param("branchId") Long branchId, @Param("status") ShiftRequestStatus status,
            Pageable pageable);
}
