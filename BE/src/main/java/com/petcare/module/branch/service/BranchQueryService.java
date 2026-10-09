package com.petcare.module.branch.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.repository.BranchLookupRepository;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.HolidayRepository;
import com.petcare.module.catalog.api.ServiceGroup;

/** Cài đặt {@link BranchQueryApi} (CN): chỉ đọc, cho identity, appointment, visit, sales, content (06 §2). */
@Service
@Transactional(readOnly = true)
public class BranchQueryService implements BranchQueryApi {

    /** Quota khi không có quota riêng của khung lẫn quota mặc định của nhóm (BR-LH-03). */
    static final int FALLBACK_QUOTA = 1;

    private final BranchRepository branches;
    private final HolidayRepository holidays;
    private final BranchLookupRepository lookup;
    private final ScheduleLoader scheduleLoader;

    public BranchQueryService(BranchRepository branches, HolidayRepository holidays, BranchLookupRepository lookup,
            ScheduleLoader scheduleLoader) {
        this.branches = branches;
        this.holidays = holidays;
        this.lookup = lookup;
        this.scheduleLoader = scheduleLoader;
    }

    @Override
    public Optional<BranchSummary> findBranch(Long branchId) {
        return branches.findById(branchId).map(BranchQueryService::toSummary);
    }

    @Override
    public List<BranchSummary> listActiveBranches() {
        return branches.findAll((root, query, cb) -> cb.equal(root.get("status"),
                        com.petcare.module.branch.entity.BranchStatus.ACTIVE), Sort.by("name"))
                .stream().map(BranchQueryService::toSummary).toList();
    }

    @Override
    public List<TimeRange> openingRangesOn(Long branchId, LocalDate date) {
        return scheduleLoader.load(branchId).rangesOn(date);
    }

    @Override
    public boolean isHoliday(Long branchId, LocalDate date) {
        return holidays.existsByBranchIdAndHolidayDate(branchId, date);
    }

    @Override
    public boolean isOpenAt(Long branchId, LocalDateTime at) {
        return scheduleLoader.load(branchId).isOpenAt(at);
    }

    @Override
    public Optional<LocalDateTime> nextOpeningStart(Long branchId, LocalDateTime after) {
        return scheduleLoader.load(branchId).nextOpeningStart(after);
    }

    @Override
    public boolean isServiceEnabled(Long branchId, Long serviceId) {
        return lookup.isServiceEnabled(branchId, serviceId);
    }

    @Override
    public List<Long> findActiveBranchIdsEnablingService(Long serviceId) {
        return lookup.findActiveBranchIdsEnablingService(serviceId);
    }

    /** Quota theo khung chỉ có với Khám/Tiêm và Thẩm mỹ; Lưu trú không có khung giờ nên là lỗi lập trình. */
    @Override
    public int quotaFor(Long branchId, ServiceGroup group, LocalDate slotDate, LocalTime slotStart) {
        if (group == ServiceGroup.BOARDING) {
            throw new IllegalArgumentException("Nhóm Lưu trú không có quota theo khung giờ");
        }
        return lookup.findSlotQuota(branchId, group.name(), slotDate, slotStart)
                .or(() -> lookup.findDefaultQuota(branchId, group.name()))
                .orElse(FALLBACK_QUOTA);
    }

    /** {@code BranchStatus} ở đây là enum của {@link BranchQueryApi}, không phải enum của entity. */
    private static BranchSummary toSummary(Branch b) {
        return new BranchSummary(b.getId(), b.getName(), b.getAddress(), b.getPhone(),
                BranchStatus.valueOf(b.getStatus().name()), b.isAcceptsAfterHoursEmergency());
    }
}
