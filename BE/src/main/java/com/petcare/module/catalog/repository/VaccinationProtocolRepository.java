package com.petcare.module.catalog.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /** I-3 (07 §11): mọi dòng đang dùng của loài, theo loại vaccine rồi mũi tăng dần. */
    List<VaccinationProtocol> findBySpeciesAndActiveTrueOrderByVaccineTypeIdAscDoseNumberAsc(Species species);

    /**
     * Khóa dòng (Hibernate phát {@code FOR NO KEY UPDATE}) cho thao tác sửa: đọc rồi ghi không khóa sẽ UPDATE tất cả cột
     * từ bản đã đọc và ghi đè thay đổi chen giữa (docs/adr/0106). Phải gọi trong transaction của use case.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM VaccinationProtocol x WHERE x.id = :id")
    Optional<VaccinationProtocol> findByIdForUpdate(@Param("id") Long id);
}
