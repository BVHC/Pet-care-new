package com.petcare.module.branch.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.branch.entity.Holiday;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByBranchIdOrderByHolidayDateAsc(Long branchId);

    List<Holiday> findByBranchIdAndHolidayDateBetweenOrderByHolidayDateAsc(Long branchId, LocalDate from, LocalDate to);

    Optional<Holiday> findByIdAndBranchId(Long id, Long branchId);

    boolean existsByBranchIdAndHolidayDate(Long branchId, LocalDate holidayDate);
}
