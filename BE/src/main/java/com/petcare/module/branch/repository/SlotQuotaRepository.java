package com.petcare.module.branch.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.branch.entity.SlotQuota;
import com.petcare.module.catalog.api.ServiceGroup;

public interface SlotQuotaRepository extends JpaRepository<SlotQuota, Long> {

    List<SlotQuota> findByBranchIdAndSlotDateBetweenOrderBySlotDateAscSlotStartAscServiceGroupAsc(Long branchId,
            LocalDate from, LocalDate to);

    List<SlotQuota> findByBranchIdAndServiceGroupAndSlotDateBetweenOrderBySlotDateAscSlotStartAsc(Long branchId,
            ServiceGroup serviceGroup, LocalDate from, LocalDate to);

    Optional<SlotQuota> findByBranchIdAndServiceGroupAndSlotDateAndSlotStart(Long branchId, ServiceGroup serviceGroup,
            LocalDate slotDate, LocalTime slotStart);

    Optional<SlotQuota> findByIdAndBranchId(Long id, Long branchId);
}
