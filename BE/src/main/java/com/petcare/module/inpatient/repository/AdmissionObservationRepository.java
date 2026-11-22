package com.petcare.module.inpatient.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.inpatient.entity.AdmissionObservation;

/** Bảng {@code admission_observations} (LOG, BR-LT-16): chỉ thêm; không ai gọi {@code delete} (docs/adr/0149). */
public interface AdmissionObservationRepository extends JpaRepository<AdmissionObservation, Long> {
}
