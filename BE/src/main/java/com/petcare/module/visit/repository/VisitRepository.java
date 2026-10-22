package com.petcare.module.visit.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;

/**
 * Bảng {@code visits}. Query đọc cho {@code VisitQueryApi} trả record projection (JPQL constructor), không nạp entity
 * vào persistence context của bên gọi (khuôn docs/adr/0050 §8); mỗi method đúng một câu SQL. Hàng đợi (visit-v1 #2–4,
 * docs/adr/0060) đọc entity trong transaction readOnly của chính module: {@link JpaSpecificationExecutor} cho bộ lọc
 * chỉ ghép điều kiện được gửi.
 */
public interface VisitRepository extends JpaRepository<Visit, Long>, JpaSpecificationExecutor<Visit> {

    /** Một Visit cho {@code VisitQueryApi.findVisit}. */
    record SummaryRow(Long visitId, Long branchId, Long customerId, Long petId, ServiceGroup group, VisitStatus status,
            Long assigneeId) {}

    /** Một lượt chưa kết thúc của nhân viên cho {@code VisitQueryApi.findUnfinishedVisitsAssignedTo}. */
    record AssignedRow(Long visitId, Long branchId, VisitStatus status) {}

    /** Một cặp lịch hẹn → Visit (nợ D028). */
    record AppointmentVisitRow(Long appointmentId, Long visitId) {}

    @Query("""
            SELECT new com.petcare.module.visit.repository.VisitRepository$SummaryRow(
                v.id, v.branchId, v.customerId, v.petId, v.serviceGroup, v.status, v.assigneeId)
            FROM Visit v WHERE v.id = :id
            """)
    Optional<SummaryRow> findSummary(@Param("id") Long id);

    /** Lượt {@code WAITING} / {@code IN_PROGRESS} gán cho nhân viên, theo {@code id} tăng dần; index {@code ix_visits_assignee_status}. */
    @Query("""
            SELECT new com.petcare.module.visit.repository.VisitRepository$AssignedRow(v.id, v.branchId, v.status)
            FROM Visit v
            WHERE v.assigneeId = :accountId
              AND v.status IN (com.petcare.module.visit.entity.VisitStatus.WAITING,
                               com.petcare.module.visit.entity.VisitStatus.IN_PROGRESS)
            ORDER BY v.id
            """)
    List<AssignedRow> findUnfinishedAssignedTo(@Param("accountId") Long accountId);

    /** Thú có lượt {@code WAITING} / {@code IN_PROGRESS}; index {@code ix_visits_pet_checked_in_at}. */
    /**
     * Hàng đợi của tôi (visit-v1 #3, docs/adr/0060): lượt gán cho nhân viên ở các trạng thái cho trước, dạng entity cho
     * mapper; index {@code ix_visits_assignee_status}. Thứ tự do service sắp (BR-TN-04).
     */
    List<Visit> findByAssigneeIdAndStatusIn(Long assigneeId, Collection<VisitStatus> statuses);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM visits WHERE pet_id = :petId AND status IN ('WAITING', 'IN_PROGRESS'))
            """, nativeQuery = true)
    boolean existsOpenByPetId(@Param("petId") Long petId);

    /**
     * Khóa một lượt ({@code FOR NO KEY UPDATE}) trước khi nạp entity — điều phối hàng đợi (docs/adr/0061). Câu vô hướng
     * để entity được nạp sau khóa, không lấy bản cũ trong persistence context. {@code MANDATORY}: query của interface
     * Spring Data không tự có transaction, ngoài transaction thì khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM visits WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    /**
     * Sắp lại hàng đợi (docs/adr/0061): khóa mọi lượt {@code WAITING} của chi nhánh trong một câu, theo {@code id} tăng
     * dần (khuôn lô kho docs/adr/0042), để hai lần sắp cùng chi nhánh chạy tuần tự. Lượt vừa được gọi trong lúc chờ khóa
     * bị loại (Postgres kiểm lại {@code WHERE} trên dòng mới). Index {@code ix_visits_queue}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id FROM visits WHERE branch_id = :branchId AND status = 'WAITING'
            ORDER BY id FOR NO KEY UPDATE
            """, nativeQuery = true)
    List<Long> lockWaitingIdsOfBranch(@Param("branchId") Long branchId);

    /**
     * D017 (03 Tài khoản#7, BR-QT-11, BR-TN-08; docs/adr/0062): lượt đã gọi ({@code IN_PROGRESS}) của người vừa bị khóa
     * → cần gán lại. Một câu, index {@code ix_visits_assignee_status}; dòng đã đánh dấu không ghi lại (khóa lại sau khi
     * mở khóa giữ {@code updated_at}). Bulk update tự đặt {@code updated_at} từ {@code Clock} (docs/adr/0015).
     * <p>
     * Chạy trong transaction khóa tài khoản của identity, lúc entity {@code Account} vừa bị sửa mà chưa flush:
     * {@code flushAutomatically} ghi nó trước, <b>không</b> {@code clearAutomatically} (clear sẽ tách entity identity
     * đang dùng tiếp). Transaction đó không nạp entity {@code Visit} nào (chỉ projection), nên không có bản cũ cần xóa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE visits SET needs_reassign = true, updated_at = :now
            WHERE assignee_id = :accountId AND status = 'IN_PROGRESS' AND needs_reassign = false
            """, nativeQuery = true)
    int flagNeedsReassign(@Param("accountId") Long accountId, @Param("now") Instant now);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM visits WHERE pet_id = :petId)", nativeQuery = true)
    boolean existsAnyByPetId(@Param("petId") Long petId);

    @Query("SELECT v.id FROM Visit v WHERE v.petId = :petId ORDER BY v.id")
    List<Long> findIdsByPetId(@Param("petId") Long petId);

    /** Đã có bệnh án hoặc mũi tiêm tại hệ thống (BR-KH-03). */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM medical_records mr JOIN visits v ON v.id = mr.visit_id WHERE v.pet_id = :petId)
                OR EXISTS (SELECT 1 FROM vaccinations WHERE pet_id = :petId)
            """, nativeQuery = true)
    boolean hasMedicalHistory(@Param("petId") Long petId);

    /**
     * Lượt đã ghi nhận ít nhất một mũi tiêm (BR-KB-02: lượt chỉ có dịch vụ loại Tiêm). Lọc cả {@code pet_id} để đi tiền
     * tố của {@code ix_vaccinations_pet_type_administered}: {@code vaccinations.visit_id} chưa có index (nợ D043,
     * docs/adr/0064 §6).
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM vaccinations WHERE pet_id = :petId AND visit_id = :visitId)
            """, nativeQuery = true)
    boolean hasVaccinationInVisit(@Param("petId") Long petId, @Param("visitId") Long visitId);

    /** Visit của các lịch hẹn; index {@code uq_visits_appointment_id}. Bên gọi không truyền tập rỗng. */
    @Query("""
            SELECT new com.petcare.module.visit.repository.VisitRepository$AppointmentVisitRow(v.appointmentId, v.id)
            FROM Visit v WHERE v.appointmentId IN :appointmentIds
            """)
    List<AppointmentVisitRow> findByAppointmentIds(@Param("appointmentIds") Collection<Long> appointmentIds);
}
