package com.petcare.module.inpatient.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.api.AdmissionQueryApi;
import com.petcare.module.inpatient.repository.AdmissionRepository;

/** Bản cài của {@link AdmissionQueryApi} (docs/adr/0149 mục 13): đọc không khóa trong transaction của bên gọi. */
@Service
public class AdmissionQueryService implements AdmissionQueryApi {

    private final AdmissionRepository admissions;

    public AdmissionQueryService(AdmissionRepository admissions) {
        this.admissions = admissions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveAdmission(Long petId) {
        if (petId == null) {
            throw new IllegalArgumentException("petId is required");
        }
        return admissions.existsActiveByPetId(petId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveAdmissionForVisit(Long visitId) {
        if (visitId == null) {
            throw new IllegalArgumentException("visitId is required");
        }
        return admissions.existsActiveByVisitId(visitId);
    }
}
