package com.petcare.module.visit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.visit.entity.MedicalRecord;

/**
 * Bảng {@code medical_records}, PK = {@code visit_id}. Không có method khóa: bên ghi khóa dòng {@code visits} trước
 * ({@code VisitRepository.lockById}, docs/adr/0066 §7) rồi mới đọc dòng này.
 */
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Long> {
}
