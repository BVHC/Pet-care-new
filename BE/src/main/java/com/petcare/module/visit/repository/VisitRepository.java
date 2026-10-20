package com.petcare.module.visit.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;

/**
 * Bảng {@code visits}. Query đọc cho {@code VisitQueryApi} trả record projection (JPQL constructor), không nạp entity
 * vào persistence context của bên gọi (khuôn docs/adr/0050 §8); mỗi method đúng một câu SQL.
 */
public interface VisitRepository extends JpaRepository<Visit, Long> {

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
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM visits WHERE pet_id = :petId AND status IN ('WAITING', 'IN_PROGRESS'))
            """, nativeQuery = true)
    boolean existsOpenByPetId(@Param("petId") Long petId);

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

    /** Visit của các lịch hẹn; index {@code uq_visits_appointment_id}. Bên gọi không truyền tập rỗng. */
    @Query("""
            SELECT new com.petcare.module.visit.repository.VisitRepository$AppointmentVisitRow(v.appointmentId, v.id)
            FROM Visit v WHERE v.appointmentId IN :appointmentIds
            """)
    List<AppointmentVisitRow> findByAppointmentIds(@Param("appointmentIds") Collection<Long> appointmentIds);
}
