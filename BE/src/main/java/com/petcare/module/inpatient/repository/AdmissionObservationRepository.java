package com.petcare.module.inpatient.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.inpatient.entity.AdmissionObservation;

/** Bảng {@code admission_observations} (LOG, BR-LT-16): chỉ thêm; không ai gọi {@code delete} (docs/adr/0149). */
public interface AdmissionObservationRepository extends JpaRepository<AdmissionObservation, Long> {

    /** inpatient-v1 #10: mới nhất trước (index {@code (admission_id, created_at DESC)}), trùng thì id giảm dần. */
    Page<AdmissionObservation> findByAdmissionIdOrderByCreatedAtDescIdDesc(Long admissionId, Pageable pageable);

    /**
     * {@code created_at} đúng như DB đã lưu. Entity giữ {@code Instant.now(clock)} đầy đủ nano giây còn
     * {@code TIMESTAMPTZ} chỉ giữ micro giây, nên response của #9 đọc lại giá trị này để khớp với #10 (docs/adr/0149
     * mục 36). Gọi sau {@code saveAndFlush} trong cùng transaction.
     */
    @Query(value = "SELECT created_at FROM admission_observations WHERE id = :id", nativeQuery = true)
    Instant findCreatedAt(@Param("id") Long id);
}
