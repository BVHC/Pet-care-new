package com.petcare.module.branch.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.branch.entity.OpeningHours;

public interface OpeningHoursRepository extends JpaRepository<OpeningHours, Long> {

    List<OpeningHours> findByBranchIdOrderByEffectiveFromAscDayOfWeekAsc(Long branchId);

    /** Có ít nhất một ngày trong tuần có khoảng giờ mở cửa ở một phiên bản nào đó (điều kiện kích hoạt, BR-CN-01). */
    boolean existsByBranchIdAndOpen1IsNotNull(Long branchId);

    long deleteByBranchIdAndEffectiveFrom(Long branchId, LocalDate effectiveFrom);
}
