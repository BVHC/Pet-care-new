package com.petcare.module.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.customer.api.Species;

public interface VaccinationProtocolRepository
        extends JpaRepository<VaccinationProtocol, Long>, JpaSpecificationExecutor<VaccinationProtocol> {

    boolean existsBySpeciesAndVaccineTypeIdAndDoseNumber(Species species, Long vaccineTypeId, int doseNumber);

    boolean existsByVaccineTypeId(Long vaccineTypeId);

    List<VaccinationProtocol> findBySpeciesAndVaccineTypeIdAndActiveTrueOrderByDoseNumberAsc(Species species,
            Long vaccineTypeId);

    List<VaccinationProtocol> findBySpeciesAndRequiredForBoardingTrueAndActiveTrue(Species species);
}
