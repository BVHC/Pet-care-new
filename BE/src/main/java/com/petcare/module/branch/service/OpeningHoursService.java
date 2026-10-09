package com.petcare.module.branch.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchClinicCancellationEvent;
import com.petcare.module.branch.api.BranchClinicCancellationEvent.Cause;
import com.petcare.module.branch.dto.OpeningHoursImpactRequest;
import com.petcare.module.branch.dto.OpeningHoursVersionResponse;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.dto.SetOpeningHoursRequest;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.OpeningHoursRepository;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.module.branch.service.ScheduleImpactFinder.Impact;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC14 — giờ mở cửa (BR-CN-02, BR-CN-04). Phiên bản mới có ngày hiệu lực; nếu làm lịch hẹn / đặt chỗ BOOKED rơi ra
 * ngoài giờ hoặc vào ngày nghỉ thì từ chối, trừ khi quản lý chọn hủy hàng loạt (phát
 * {@link BranchClinicCancellationEvent}, cách hủy và thông báo do appointment / boarding làm — BR-LH-10).
 */
@Service
public class OpeningHoursService {

    private final BranchRepository branches;
    private final OpeningHoursRepository openingHours;
    private final ScheduleLoader scheduleLoader;
    private final ScheduleImpactFinder impactFinder;
    private final ApplicationEventPublisher events;
    private final BranchScope scope;
    private final Clock clock;

    public OpeningHoursService(BranchRepository branches, OpeningHoursRepository openingHours,
            ScheduleLoader scheduleLoader, ScheduleImpactFinder impactFinder, ApplicationEventPublisher events,
            BranchScope scope, Clock clock) {
        this.branches = branches;
        this.openingHours = openingHours;
        this.scheduleLoader = scheduleLoader;
        this.impactFinder = impactFinder;
        this.events = events;
        this.scope = scope;
        this.clock = clock;
    }

    /** Bản đang áp dụng hôm nay và các bản tương lai. */
    @Transactional(readOnly = true)
    public List<OpeningHoursVersionResponse> listVersions(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        LocalDate today = LocalDate.now(clock);
        List<Version> all = scheduleLoader.loadVersions(branchId);
        LocalDate current = all.stream().map(Version::effectiveFrom).filter(d -> !d.isAfter(today))
                .max(LocalDate::compareTo).orElse(null);
        return all.stream().filter(v -> current == null || !v.effectiveFrom().isBefore(current))
                .map(OpeningHoursMapping::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleImpact previewImpact(Long branchId, OpeningHoursImpactRequest request) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        Version version = OpeningHoursMapping.fromRequest(request.effectiveFrom(), request.days());
        if (branch.getStatus() == BranchStatus.DRAFT) {
            return impactFinder.describe(Impact.NONE);
        }
        BranchSchedule next = scheduleLoader.load(branchId).withVersion(version);
        return impactFinder.describe(impactFinder.forHoursChange(branchId, next, request.effectiveFrom()));
    }

    @Transactional
    public OpeningHoursVersionResponse setOpeningHours(Long branchId, SetOpeningHoursRequest request) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        Version version = OpeningHoursMapping.fromRequest(request.effectiveFrom(), request.days());
        checkEffectiveFrom(branch, request.effectiveFrom());

        // Chi nhánh DRAFT chưa nhận đặt lịch (BR-CN-01) nên không có lịch hẹn / đặt chỗ nào để bị ảnh hưởng.
        Impact impact = branch.getStatus() == BranchStatus.DRAFT ? Impact.NONE
                : impactFinder.forHoursChange(branchId, scheduleLoader.load(branchId).withVersion(version),
                        request.effectiveFrom());
        boolean cancelAffected = Boolean.TRUE.equals(request.cancelAffected());
        if (!impact.isEmpty() && !cancelAffected) {
            throw new BusinessRuleViolationException("BR-CN-04", "Giờ mở cửa mới làm " + impact.appointments().size()
                    + " lịch hẹn và " + impact.stays().size()
                    + " đặt chỗ lưu trú rơi ra ngoài giờ mở cửa; xem danh sách và chọn hủy hàng loạt để tiếp tục");
        }

        openingHours.deleteByBranchIdAndEffectiveFrom(branchId, request.effectiveFrom());
        openingHours.flush();
        openingHours.saveAll(OpeningHoursMapping.toRows(branchId, version));

        if (!impact.isEmpty()) {
            events.publishEvent(new BranchClinicCancellationEvent(branchId, Cause.OPENING_HOURS_REDUCED,
                    impact.appointments().stream().map(s -> s.appointmentId()).toList(),
                    impact.stays().stream().map(b -> b.bookingId()).toList(), scope.current().accountId()));
        }
        return OpeningHoursMapping.toResponse(version);
    }

    /**
     * BR-CN-04: ngày hiệu lực sớm nhất là ngày mai. Chi nhánh {@code DRAFT} chưa nhận đặt lịch nên được đặt giờ có
     * hiệu lực từ hôm nay (branch-v1 A2).
     */
    private void checkEffectiveFrom(Branch branch, LocalDate effectiveFrom) {
        LocalDate today = LocalDate.now(clock);
        LocalDate earliest = branch.getStatus() == BranchStatus.DRAFT ? today : today.plusDays(1);
        if (effectiveFrom.isBefore(earliest)) {
            throw new BusinessRuleViolationException("BR-CN-04",
                    branch.getStatus() == BranchStatus.DRAFT ? "Ngày hiệu lực không được ở quá khứ"
                            : "Ngày hiệu lực của giờ mở cửa mới sớm nhất là ngày mai");
        }
    }

    private static ResourceNotFoundException notFound(Long branchId) {
        return new ResourceNotFoundException("Chi nhánh", branchId);
    }
}
