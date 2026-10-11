package com.petcare.module.branch.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchClinicCancellationEvent;
import com.petcare.module.branch.api.BranchClinicCancellationEvent.Cause;
import com.petcare.module.branch.dto.CreateHolidayRequest;
import com.petcare.module.branch.dto.HolidayImpactRequest;
import com.petcare.module.branch.dto.HolidayResponse;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.entity.Holiday;
import com.petcare.module.branch.mapper.BranchMapper;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.HolidayRepository;
import com.petcare.module.branch.service.ScheduleImpactFinder.Impact;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC14 — ngày nghỉ (BR-CN-03). Thêm ngày nghỉ vào ngày còn lịch hẹn / đặt chỗ BOOKED bị từ chối (BR-LH-10), trừ khi
 * quản lý chọn hủy hàng loạt. Lịch đã bị hủy không khôi phục khi xóa ngày nghỉ (branch-v1 A3).
 */
@Service
public class HolidayService {

    private final BranchRepository branches;
    private final HolidayRepository holidays;
    private final ScheduleImpactFinder impactFinder;
    private final ApplicationEventPublisher events;
    private final BranchScope scope;
    private final BranchMapper mapper;
    private final Clock clock;

    public HolidayService(BranchRepository branches, HolidayRepository holidays, ScheduleImpactFinder impactFinder,
            ApplicationEventPublisher events, BranchScope scope, BranchMapper mapper, Clock clock) {
        this.branches = branches;
        this.holidays = holidays;
        this.impactFinder = impactFinder;
        this.events = events;
        this.scope = scope;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<HolidayResponse> listHolidays(Long branchId, LocalDate from, LocalDate to) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        List<Holiday> found = holidays.findByBranchIdOrderByHolidayDateAsc(branchId);
        return found.stream()
                .filter(h -> (from == null || !h.getHolidayDate().isBefore(from))
                        && (to == null || !h.getHolidayDate().isAfter(to)))
                .map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleImpact previewImpact(Long branchId, HolidayImpactRequest request) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        return impactFinder.describe(impactOf(branch, request.holidayDate()));
    }

    @Transactional
    public HolidayResponse addHoliday(Long branchId, CreateHolidayRequest request) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        LocalDate date = request.holidayDate();
        if (date.isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-CN-03", "Không thêm được ngày nghỉ trong quá khứ");
        }
        if (holidays.existsByBranchIdAndHolidayDate(branchId, date)) {
            throw new BusinessRuleViolationException("BR-CN-03", "Ngày " + date + " đã là ngày nghỉ của chi nhánh");
        }

        Impact impact = impactOf(branch, date);
        if (!impact.isEmpty() && !Boolean.TRUE.equals(request.cancelAffected())) {
            throw new BusinessRuleViolationException("BR-LH-10", "Ngày " + date + " còn " + impact.appointments().size()
                    + " lịch hẹn và " + impact.stays().size()
                    + " đặt chỗ lưu trú; xem danh sách và chọn hủy hàng loạt để tiếp tục");
        }

        Holiday holiday = holidays.saveAndFlush(
                new Holiday(branchId, date, request.reason(), scope.current().accountId()));
        if (!impact.isEmpty()) {
            events.publishEvent(new BranchClinicCancellationEvent(branchId, Cause.HOLIDAY_ADDED,
                    impact.appointments().stream().map(s -> s.appointmentId()).toList(),
                    impact.stays().stream().map(b -> b.bookingId()).toList(), scope.current().accountId()));
        }
        return mapper.toResponse(holiday);
    }

    /** Chỉ xóa được ngày nghỉ trong tương lai (ngày mai trở đi). */
    @Transactional
    public void deleteHoliday(Long branchId, Long holidayId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        Holiday holiday = holidays.findByIdAndBranchId(holidayId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Ngày nghỉ", holidayId));
        if (!holiday.getHolidayDate().isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-CN-03", "Chỉ xóa được ngày nghỉ trong tương lai");
        }
        holidays.delete(holiday);
        holidays.flush();
    }

    /** Chi nhánh DRAFT chưa nhận đặt lịch (BR-CN-01) nên không có lịch hẹn / đặt chỗ nào để bị ảnh hưởng. */
    private Impact impactOf(Branch branch, LocalDate date) {
        return branch.getStatus() == BranchStatus.DRAFT ? Impact.NONE : impactFinder.forHoliday(branch.getId(), date);
    }

    private static ResourceNotFoundException notFound(Long branchId) {
        return new ResourceNotFoundException("Chi nhánh", branchId);
    }
}
