package com.petcare.module.branch.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.petcare.module.branch.entity.Holiday;
import com.petcare.module.branch.entity.OpeningHours;
import com.petcare.module.branch.repository.HolidayRepository;
import com.petcare.module.branch.repository.OpeningHoursRepository;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;

/** Dựng {@link BranchSchedule} của một chi nhánh từ {@code opening_hours} và {@code holidays}. */
@Component
class ScheduleLoader {

    private final OpeningHoursRepository hours;
    private final HolidayRepository holidays;

    ScheduleLoader(OpeningHoursRepository hours, HolidayRepository holidays) {
        this.hours = hours;
        this.holidays = holidays;
    }

    BranchSchedule load(Long branchId) {
        return new BranchSchedule(loadVersions(branchId),
                holidays.findByBranchIdOrderByHolidayDateAsc(branchId).stream().map(Holiday::getHolidayDate).toList());
    }

    List<Version> loadVersions(Long branchId) {
        Map<LocalDate, List<OpeningHours>> byDate = new TreeMap<>();
        for (OpeningHours row : hours.findByBranchIdOrderByEffectiveFromAscDayOfWeekAsc(branchId)) {
            byDate.computeIfAbsent(row.getEffectiveFrom(), d -> new ArrayList<>()).add(row);
        }
        List<Version> versions = new ArrayList<>();
        byDate.forEach((date, rows) -> versions.add(OpeningHoursMapping.toVersion(date, rows)));
        return versions;
    }
}
