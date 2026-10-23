package com.petcare.module.branch.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.dto.DayHoursDto;
import com.petcare.module.branch.dto.PublicBranchResponse;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC15 — chi nhánh công khai (branch-v1 #20–21; BR-CK-01, BR-CN-05). Chỉ chi nhánh {@code ACTIVE}; chi nhánh DRAFT hoặc
 * không có đều là 404 ở chi tiết, danh sách không bao giờ có chúng. Giờ mở cửa là bản có {@code effective_from} lớn
 * nhất ≤ hôm nay (không hiện bản tương lai); thứ không có khoảng nào là ngày nghỉ cố định.
 */
@Service
@Transactional(readOnly = true)
public class PublicBranchService {

    private final BranchRepository branches;
    private final ScheduleLoader scheduleLoader;
    private final Clock clock;

    public PublicBranchService(BranchRepository branches, ScheduleLoader scheduleLoader, Clock clock) {
        this.branches = branches;
        this.scheduleLoader = scheduleLoader;
        this.clock = clock;
    }

    public List<PublicBranchResponse> list(Boolean acceptsAfterHoursEmergency) {
        LocalDate today = LocalDate.now(clock);
        return branches.findAll((root, query, cb) -> cb.equal(root.get("status"), BranchStatus.ACTIVE),
                        Sort.by("name", "id")).stream()
                .filter(b -> acceptsAfterHoursEmergency == null
                        || b.isAcceptsAfterHoursEmergency() == acceptsAfterHoursEmergency)
                .map(b -> toResponse(b, today)).toList();
    }

    public PublicBranchResponse get(Long branchId) {
        Branch branch = branches.findById(branchId).filter(b -> b.getStatus() == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        return toResponse(branch, LocalDate.now(clock));
    }

    private PublicBranchResponse toResponse(Branch branch, LocalDate today) {
        List<Version> versions = scheduleLoader.loadVersions(branch.getId());
        List<DayHoursDto> weekly = versions.stream().filter(v -> !v.effectiveFrom().isAfter(today))
                .max((a, b) -> a.effectiveFrom().compareTo(b.effectiveFrom()))
                .map(v -> OpeningHoursMapping.toResponse(v).days())
                .orElseGet(() -> OpeningHoursMapping.toResponse(new Version(today, java.util.Map.of())).days());
        return new PublicBranchResponse(branch.getId(), branch.getName(), branch.getAddress(), branch.getPhone(),
                branch.getLatitude(), branch.getLongitude(), branch.isAcceptsAfterHoursEmergency(), weekly);
    }
}
