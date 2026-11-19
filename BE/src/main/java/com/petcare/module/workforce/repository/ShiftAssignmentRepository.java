package com.petcare.module.workforce.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;

import jakarta.persistence.LockModeType;

/** Bảng {@code shift_assignments} (docs/adr/0138). Hàm khóa là {@code MANDATORY}, xem {@code ShiftDefinitionRepository}. */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {

    /** {@code FOR NO KEY UPDATE}: hủy, ghi vắng, duyệt đổi / nghỉ (thứ tự khóa ở ADR-0123 mục 8, workforce-v1 §D.5). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ShiftAssignment a WHERE a.id = :id")
    Optional<ShiftAssignment> findByIdForUpdate(@Param("id") Long id);

    /** Chủ và chi nhánh của một phân ca, đọc <b>không khóa</b> để phân quyền dữ liệu trước khi khóa (docs/adr/0138 bước 4). */
    interface AssignmentScope {
        Long getStaffId();

        Long getBranchId();
    }

    /**
     * Phạm vi của phân ca để kiểm 404 / 403 trước mọi khóa; hai cột này {@code updatable = false} nên đọc không khóa
     * không bị lỗi thời. Rỗng = không có phân ca.
     */
    @Query("SELECT a.staffId AS staffId, a.branchId AS branchId FROM ShiftAssignment a WHERE a.id = :id")
    Optional<AssignmentScope> findScopeById(@Param("id") Long id);

    /**
     * Lịch của chính một nhân viên trong {@code [from, to]} (workforce-v1 #8), <b>mọi trạng thái</b> và mọi chi nhánh,
     * sắp {@code workDate}, giờ bắt đầu của ca, {@code id} tăng dần. Không phân trang: khoảng tối đa 62 ngày và các phân
     * ca {@code SCHEDULED} của một người không chồng giờ. Đi qua {@code ix_shift_assignments_staff_date}.
     */
    @Query("""
            SELECT a FROM ShiftAssignment a, ShiftDefinition d
            WHERE d.id = a.shiftDefinitionId AND a.staffId = :staffId
              AND a.workDate >= :from AND a.workDate <= :to
            ORDER BY a.workDate ASC, d.startTime ASC, a.id ASC
            """)
    List<ShiftAssignment> findStaffSchedule(@Param("staffId") Long staffId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Một phân ca {@code SCHEDULED} đang giao giờ với ca mới (để báo lỗi nêu đúng phân ca đang chồng). */
    interface OverlappingShift {
        Long getAssignmentId();

        String getShiftName();

        LocalTime getStartTime();

        LocalTime getEndTime();
    }

    /**
     * Phân ca {@code SCHEDULED} của nhân viên trong ngày có khoảng giờ <b>giao thật sự</b> với {@code [start, end)}:
     * {@code startA < endB và startB < endA}, ca liền kề (kết thúc = bắt đầu) không tính (BR-NS-02, docs/adr/0138). Cùng
     * một ca trong cùng ngày cũng giao nhau nên trùng cặp bị bắt ở đây trước khi chạm unique index. Chạy dưới khóa
     * {@code accounts} của nhân viên; status là literal để dùng {@code ix_shift_assignments_staff_date}; {@code LocalTime}
     * bind qua {@code CAST(… AS time)} để PostgreSQL suy được kiểu.
     */
    @Query(value = """
            SELECT a.id AS assignmentId, d.name AS shiftName, d.start_time AS startTime, d.end_time AS endTime
            FROM shift_assignments a JOIN shift_definitions d ON d.id = a.shift_definition_id
            WHERE a.staff_id = :staffId AND a.work_date = :workDate AND a.status = 'SCHEDULED'
              AND d.start_time < CAST(:endTime AS time) AND CAST(:startTime AS time) < d.end_time
            ORDER BY d.start_time ASC, a.id ASC
            """, nativeQuery = true)
    List<OverlappingShift> findOverlapping(@Param("staffId") Long staffId, @Param("workDate") LocalDate workDate,
            @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime);

    /** Một phân ca {@code SCHEDULED} chưa bắt đầu bị hủy vì nhân viên rời chi nhánh (đủ dữ liệu để dựng thông báo). */
    interface CancellableAssignment {
        Long getAssignmentId();

        LocalDate getWorkDate();

        String getShiftName();

        LocalTime getStartTime();

        LocalTime getEndTime();
    }

    /**
     * BR-NS-09: phân ca {@code SCHEDULED} của nhân viên tại chi nhánh <b>cũ</b> mà <b>chưa bắt đầu</b>
     * ({@code work_date + start_time > now}, đúng giờ bắt đầu là đã bắt đầu như #6), đọc và khóa
     * {@code FOR NO KEY UPDATE} trong <b>một</b> câu, theo {@code id} tăng dần (thứ tự khóa cố định). Cùng
     * {@code status = 'SCHEDULED'} literal nên dùng được {@code ix_shift_assignments_staff_date} ({@code work_date >=
     * today} thu hẹp trước). {@code now} là giờ Việt Nam từ {@code Clock}, bind {@code CAST(… AS timestamp)} như
     * {@code LocalTime} ở {@link #findOverlapping}. Alias SQL phải trùng tên getter của {@link CancellableAssignment}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT a.id AS assignmentId, a.work_date AS workDate, d.name AS shiftName,
                   d.start_time AS startTime, d.end_time AS endTime
            FROM shift_assignments a JOIN shift_definitions d ON d.id = a.shift_definition_id
            WHERE a.staff_id = :staffId AND a.branch_id = :branchId AND a.status = 'SCHEDULED'
              AND a.work_date >= :today AND (a.work_date + d.start_time) > CAST(:now AS timestamp)
            ORDER BY a.id ASC
            FOR NO KEY UPDATE OF a
            """, nativeQuery = true)
    List<CancellableAssignment> lockNotStartedScheduled(@Param("staffId") Long staffId,
            @Param("branchId") Long branchId, @Param("today") LocalDate today, @Param("now") LocalDateTime now);

    /**
     * Phân ca#6: {@code SCHEDULED → CANCELLED} do hệ thống ({@code status_changed_by} NULL, 03). Ghi đủ cột như
     * {@code ShiftAssignment.cancel} (CHECK V36 {@code ck_shift_assignments_status_changed} / {@code _reason}); {@code
     * updated_at} lấy từ {@code Clock} (ADR-0015). {@code status = 'SCHEDULED'} nằm trong câu để kiểm lại sau khóa.
     * Bên gọi không truyền tập rỗng ({@code IN ()} là SQL sai) và đã khóa các dòng này bằng
     * {@link #lockNotStartedScheduled}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE shift_assignments SET status = 'CANCELLED', reason = :reason, status_changed_by = NULL,
                   status_changed_at = :now, updated_at = :now
            WHERE id IN (:ids) AND status = 'SCHEDULED'
            """, nativeQuery = true)
    int cancelByStaffLeaving(@Param("ids") Collection<Long> ids, @Param("reason") String reason,
            @Param("now") Instant now);

    /**
     * Lịch phân ca của chi nhánh trong {@code [from, to]} (workforce-v1 #4), sắp {@code workDate}, giờ bắt đầu của ca,
     * {@code id} tăng dần. {@code staffId} và {@code status} tùy chọn ({@code null} = không lọc). Điều kiện cố định
     * {@code branch_id} + khoảng ngày đi qua {@code ix_shift_assignments_branch_date}; hai điều kiện tùy chọn chỉ lọc
     * tiếp trên tập đã thu hẹp. Phải truyền {@code Pageable} chưa sắp (thứ tự nằm trong câu).
     */
    @Query(value = """
            SELECT a FROM ShiftAssignment a, ShiftDefinition d
            WHERE d.id = a.shiftDefinitionId AND a.branchId = :branchId
              AND a.workDate >= :from AND a.workDate <= :to
              AND (:staffId IS NULL OR a.staffId = :staffId)
              AND (:status IS NULL OR a.status = :status)
            ORDER BY a.workDate ASC, d.startTime ASC, a.id ASC
            """, countQuery = """
            SELECT count(a) FROM ShiftAssignment a
            WHERE a.branchId = :branchId AND a.workDate >= :from AND a.workDate <= :to
              AND (:staffId IS NULL OR a.staffId = :staffId)
              AND (:status IS NULL OR a.status = :status)
            """)
    Page<ShiftAssignment> findBranchSchedule(@Param("branchId") Long branchId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("staffId") Long staffId,
            @Param("status") ShiftAssignmentStatus status, Pageable pageable);

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
