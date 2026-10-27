package com.petcare.module.care.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.entity.CareTask;

import jakarta.persistence.LockModeType;

/**
 * Bảng {@code care_tasks} (docs/adr/0079). Mọi câu ghi native là {@code MANDATORY}: bên gọi (visit, boarding, job) mở
 * transaction. Trạng thái và loại task là <b>literal</b> trong SQL để predicate của index partial khớp (CLAUDE.md
 * <i>Native SQL</i>); {@code updated_at} tự đặt từ {@code Clock} vì câu native không qua listener (docs/adr/0015).
 */
public interface CareTaskRepository extends JpaRepository<CareTask, Long>, JpaSpecificationExecutor<CareTask> {

    /**
     * Chi nhánh của task, để phân quyền (404 → 403) <b>trước</b> khi khóa (docs/adr/0080). Câu vô hướng, không nạp entity:
     * nạp trước rồi {@link #findByIdForUpdate} sẽ trả bản cũ trong persistence context. {@code branch_id} NOT NULL nên
     * rỗng nghĩa là không có task.
     */
    @Query(value = "SELECT branch_id FROM care_tasks WHERE id = :id", nativeQuery = true)
    Optional<Long> findBranchIdById(@Param("id") Long id);

    /**
     * Khóa dòng để hoàn tất / hủy (care-v1 #3, #4): {@code FOR NO KEY UPDATE} (Hibernate 6). Câu hủy hệ thống
     * ({@code cancelOpen*}) chờ khóa này rồi kiểm lại {@code status = 'OPEN'} (READ COMMITTED), nên task lễ tân vừa xử lý
     * không bị hủy đè. {@code MANDATORY}: ngoài transaction khóa nhả ngay mà không báo lỗi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM CareTask t WHERE t.id = :id")
    Optional<CareTask> findByIdForUpdate(@Param("id") Long id);

    /**
     * Care Task#1 loại {@code VACCINE_OVERDUE}, tối đa 1 task mỗi mũi (BR-TB-04). {@code ON CONFLICT DO NOTHING} thay vì
     * {@code save} rồi bắt lỗi trùng: vi phạm unique làm PostgreSQL hủy cả transaction của bên gọi. Trả 0 khi đã có task
     * (kể cả đã {@code DONE} / {@code CANCELLED}: unique index không phân biệt trạng thái, 06 §8 Q1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, vaccination_id, due_date, status,
                                    created_at, updated_at)
            VALUES (:branchId, :customerId, :petId, 'VACCINE_OVERDUE', :vaccinationId, :dueDate, 'OPEN', :now, :now)
            ON CONFLICT (vaccination_id) WHERE task_type = 'VACCINE_OVERDUE' DO NOTHING
            """, nativeQuery = true)
    int insertVaccineOverdueIfAbsent(@Param("branchId") Long branchId, @Param("customerId") Long customerId,
            @Param("petId") Long petId, @Param("vaccinationId") Long vaccinationId, @Param("dueDate") LocalDate dueDate,
            @Param("now") Instant now);

    /** Care Task#1 loại {@code PICKUP_OVERDUE}, tối đa 1 task mỗi đặt chỗ (erd §11 [ERD]); cùng cách làm như trên. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, boarding_booking_id, due_date, status,
                                    created_at, updated_at)
            VALUES (:branchId, :customerId, :petId, 'PICKUP_OVERDUE', :bookingId, :dueDate, 'OPEN', :now, :now)
            ON CONFLICT (boarding_booking_id) WHERE task_type = 'PICKUP_OVERDUE' DO NOTHING
            """, nativeQuery = true)
    int insertPickupOverdueIfAbsent(@Param("branchId") Long branchId, @Param("customerId") Long customerId,
            @Param("petId") Long petId, @Param("bookingId") Long bookingId, @Param("dueDate") LocalDate dueDate,
            @Param("now") Instant now);

    /** Cùng predicate với {@code uq_care_tasks_vaccine_overdue} nên tối đa một dòng và đi qua index đó. */
    @Query(value = "SELECT id FROM care_tasks WHERE vaccination_id = :vaccinationId AND task_type = 'VACCINE_OVERDUE'",
            nativeQuery = true)
    Optional<Long> findVaccineOverdueId(@Param("vaccinationId") Long vaccinationId);

    /** Cùng predicate với {@code uq_care_tasks_pickup_overdue}. */
    @Query(value = "SELECT id FROM care_tasks WHERE boarding_booking_id = :bookingId AND task_type = 'PICKUP_OVERDUE'",
            nativeQuery = true)
    Optional<Long> findPickupOverdueId(@Param("bookingId") Long bookingId);

    /**
     * Care Task#5: task {@code VACCINE_DUE} / {@code VACCINE_OVERDUE} còn {@code OPEN} của các mũi đã được tiêm lại →
     * {@code CANCELLED}, lý do do hệ thống đặt, {@code handled_by} để NULL (= hệ thống), {@code handled_at} = lúc hủy.
     * {@code status = 'OPEN'} nằm trong câu UPDATE nên task đã {@code DONE} / {@code CANCELLED} không đổi, và dòng vừa bị
     * lễ tân hoàn tất trong lúc chờ khóa cũng không bị hủy (READ COMMITTED kiểm lại điều kiện). Bên gọi không truyền tập
     * rỗng ({@code IN ()} là SQL sai). Đi {@code ix_care_tasks_vaccination_id} (V22, docs/adr/0081).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = :reason, handled_at = :now, updated_at = :now
            WHERE status = 'OPEN' AND task_type IN ('VACCINE_DUE', 'VACCINE_OVERDUE')
              AND vaccination_id IN (:vaccinationIds)
            """, nativeQuery = true)
    int cancelOpenVaccineTasks(@Param("vaccinationIds") Collection<Long> vaccinationIds,
            @Param("reason") String reason, @Param("now") Instant now);

    /**
     * Care Task#6: task {@code FOLLOW_UP_DUE} còn {@code OPEN} của các bệnh án (khóa là id Visit) có nhắc tái khám bị
     * hủy → {@code CANCELLED}. Cùng cách ghi như {@link #cancelOpenVaccineTasks}; đi {@code ix_care_tasks_medical_record_id} (V22, docs/adr/0081).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = :reason, handled_at = :now, updated_at = :now
            WHERE status = 'OPEN' AND task_type = 'FOLLOW_UP_DUE' AND medical_record_id IN (:medicalRecordIds)
            """, nativeQuery = true)
    int cancelOpenFollowUpTasks(@Param("medicalRecordIds") Collection<Long> medicalRecordIds,
            @Param("reason") String reason, @Param("now") Instant now);

    /**
     * Care Task#4 (ST19): mọi task còn {@code OPEN} của thú đã mất → {@code CANCELLED}, mọi loại. Cùng cách ghi như
     * {@link #cancelOpenVaccineTasks}; đi {@code ix_care_tasks_pet_id} (V13).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = :reason, handled_at = :now, updated_at = :now
            WHERE status = 'OPEN' AND pet_id = :petId
            """, nativeQuery = true)
    int cancelOpenTasksOfPet(@Param("petId") Long petId, @Param("reason") String reason, @Param("now") Instant now);

    /**
     * Chuyển chủ (06 §8 Q1): task còn {@code OPEN} của thú đổi {@code customer_id} sang chủ mới; loại, chi nhánh, hạn giữ
     * nguyên. Task đã đóng giữ chủ lúc đóng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE care_tasks SET customer_id = :customerId, updated_at = :now
            WHERE status = 'OPEN' AND pet_id = :petId AND customer_id <> :customerId
            """, nativeQuery = true)
    int moveOpenTasksToCustomer(@Param("petId") Long petId, @Param("customerId") Long customerId,
            @Param("now") Instant now);
}
