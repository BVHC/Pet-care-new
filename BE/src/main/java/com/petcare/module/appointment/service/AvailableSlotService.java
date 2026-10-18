package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.SlotResponse;
import com.petcare.module.appointment.mapper.SlotMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.SlotBookedCount;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Xem khung giờ trống — appointment-v1 #1 (UC39; BR-LH-01…04, BR-CN-01; docs/adr/0042). Chỉ đọc, không khóa
 * (docs/adr/0041 §1): một transaction {@code readOnly} + {@code REPEATABLE_READ} để mọi câu đọc (chi nhánh, dịch vụ, giờ
 * mở cửa, ngày nghỉ, số đã đặt, quota) cùng một snapshot. Khung hiển thị {@code AVAILABLE} không giữ chỗ: đặt lịch kiểm lại
 * dưới khóa (docs/adr/0041 §4).
 * <p>
 * Thứ tự: phạm vi lễ tân (trước mọi lần đọc DB) → chi nhánh tồn tại (khách: DRAFT cũng 404 cùng message, BR-CN-01) →
 * dịch vụ tồn tại → chi nhánh DRAFT (lễ tân, 400 BR-LH-01) → dịch vụ đặt được tại chi nhánh (400 BR-LH-01) → cửa sổ
 * BR-LH-04 → khung của ngày (BR-LH-02) → đếm + quota (BR-LH-03). Khách không đi qua {@code BranchScope} (docs/adr/0006
 * §4); không kiểm BR-TK-19, BR-LH-09 ở đây (chặn khi đặt lịch).
 */
@Service
public class AvailableSlotService {

    static final String CUSTOMER = "CUSTOMER";
    static final String RECEPTIONIST = "RECEPTIONIST";

    private final BranchScope branchScope;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi configs;
    private final AppointmentRepository appointments;
    private final SlotMapper mapper;
    private final Clock clock;

    public AvailableSlotService(BranchScope branchScope, BranchQueryApi branches, CatalogQueryApi catalog,
            SystemConfigApi configs, AppointmentRepository appointments, SlotMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.branches = branches;
        this.catalog = catalog;
        this.configs = configs;
        this.appointments = appointments;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SlotResponse> listAvailableSlots(Long branchId, Long serviceId, LocalDate date) {
        SecurityPrincipal principal = branchScope.current();
        boolean customer = switch (principal.role()) {
            case CUSTOMER -> true;
            case RECEPTIONIST -> {
                branchScope.check(branchId);
                yield false;
            }
            default -> throw new IllegalStateException("Role không được xem khung giờ trống: " + principal.role());
        };

        BranchSummary branch = branches.findBranch(branchId)
                .filter(found -> !customer || found.status() == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        ServiceInfo service = catalog.findService(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("dịch vụ", serviceId));
        if (branch.status() != BranchStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-LH-01", "Chi nhánh chưa nhận đặt lịch");
        }
        if (!service.active() || service.group() == ServiceGroup.BOARDING
                || !branches.isServiceEnabled(branchId, serviceId)) {
            throw new BusinessRuleViolationException("BR-LH-01", "Dịch vụ không đặt được tại chi nhánh này");
        }

        BookingWindow window = customer
                ? BookingWindow.forCustomer(clock.instant(), configs.getInt(ConfigKey.APPOINTMENT_MIN_LEAD_HOURS),
                        configs.getInt(ConfigKey.APPOINTMENT_MAX_ADVANCE_DAYS))
                : BookingWindow.forStaff(clock.instant());
        if (!window.mayContain(date)) {
            return List.of();
        }
        List<LocalTime> starts = SlotGrid.slotStarts(branches.openingRangesOn(branchId, date)).stream()
                .filter(start -> window.allows(date, start))
                .toList();
        if (starts.isEmpty()) {
            return List.of();
        }

        Map<LocalTime, Integer> booked = new HashMap<>();
        for (SlotBookedCount row : appointments.countActiveBySlot(branchId, service.group().name(), date)) {
            booked.put(LocalTime.ofSecondOfDay(row.getSecondOfDay()), row.getBooked());
        }
        return starts.stream()
                .map(start -> mapper.toSlotResponse(new SlotAvailability(start,
                        branches.quotaFor(branchId, service.group(), date, start), booked.getOrDefault(start, 0))))
                .toList();
    }
}
