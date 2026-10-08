package com.petcare.module.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.catalog.entity.VaccineType;
import com.petcare.module.customer.api.Species;

public interface VaccineTypeRepository extends JpaRepository<VaccineType, Long>, JpaSpecificationExecutor<VaccineType> {

    boolean existsByNameAndSpecies(String name, Species species);

    boolean existsByNameAndSpeciesAndIdNot(String name, Species species, Long id);
}
