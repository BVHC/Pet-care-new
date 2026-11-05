package com.petcare.module.visit.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.visit.entity.MedicalRecordAddendum;

/**
 * Bảng {@code medical_record_addenda} (LOG, chỉ thêm mới). Cột {@code medical_record_id} có index từ V28 (docs/adr/0093).
 * DB không chặn UPDATE / DELETE bản bổ sung (trigger bị chấp nhận không làm, ADR-0093): chỉ service giữ "chỉ thêm".
 */
public interface MedicalRecordAddendumRepository extends JpaRepository<MedicalRecordAddendum, Long> {

    /** Bản bổ sung của một bệnh án theo thứ tự ghi. */
    List<MedicalRecordAddendum> findByMedicalRecordIdOrderByIdAsc(Long medicalRecordId);

    /** Bản bổ sung của nhiều bệnh án một câu (hồ sơ sức khỏe, visit-v1 #21), theo thứ tự ghi. */
    List<MedicalRecordAddendum> findByMedicalRecordIdInOrderByIdAsc(Collection<Long> medicalRecordIds);
}
