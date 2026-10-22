package com.petcare.module.visit.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.visit.entity.MedicalRecordAddendum;

/**
 * Bảng {@code medical_record_addenda} (LOG, chỉ thêm mới). Cột {@code medical_record_id} chưa có index (nợ D043): đọc
 * bản bổ sung của một bệnh án quét bảng cho tới khi trả nợ.
 */
public interface MedicalRecordAddendumRepository extends JpaRepository<MedicalRecordAddendum, Long> {

    /** Bản bổ sung của một bệnh án theo thứ tự ghi. */
    List<MedicalRecordAddendum> findByMedicalRecordIdOrderByIdAsc(Long medicalRecordId);
}
