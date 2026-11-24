package com.petcare.module.inpatient.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;

import jakarta.persistence.LockModeType;

/** Bảng {@code admission_orders} (docs/adr/0149). Hàm khóa là {@code MANDATORY}, xem {@link AdmissionRepository}. */
public interface AdmissionOrderRepository extends JpaRepository<AdmissionOrder, Long> {

    /**
     * Số y lệnh còn {@code ORDERED} của ca (chặn xuất viện, BR-LT-14). Literal {@code ORDERED} để planner dùng index
     * {@code (admission_id, status, scheduled_at)} V38. Không khóa: bên gọi đã khóa dòng {@code admissions}, và ra y lệnh
     * phải khóa dòng đó trước khi INSERT nên phép đếm này không bị vượt mặt (docs/adr/0149 mục 22).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            SELECT COUNT(o) FROM AdmissionOrder o
            WHERE o.admissionId = :admissionId
              AND o.status = com.petcare.module.inpatient.entity.AdmissionOrderStatus.ORDERED""")
    long countOrderedByAdmissionId(@Param("admissionId") Long admissionId);

    /** inpatient-v1 #6: y lệnh của ca, sắp {@code scheduledAt} tăng dần, trùng thì id tăng dần (index V38 phủ sắp xếp). */
    List<AdmissionOrder> findByAdmissionIdOrderByScheduledAtAscIdAsc(Long admissionId);

    /** inpatient-v1 #6 với {@code status}. */
    List<AdmissionOrder> findByAdmissionIdAndStatusOrderByScheduledAtAscIdAsc(Long admissionId,
            AdmissionOrderStatus status);

    /** {@code FOR NO KEY UPDATE}: thực hiện hoặc hủy một y lệnh. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM AdmissionOrder o WHERE o.id = :id")
    Optional<AdmissionOrder> findByIdForUpdate(@Param("id") Long id);
}
