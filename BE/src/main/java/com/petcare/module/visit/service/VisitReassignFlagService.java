package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.visit.repository.VisitRepository;

/**
 * Hệ quả trên visit của khóa tài khoản (03 Tài khoản#7 "Visit {@code IN_PROGRESS} đánh dấu cần gán lại", BR-QT-11,
 * BR-TN-08, 06 §3; trả nợ D017 — docs/adr/0062). Chỉ gọi từ {@code visit/listener/AccountLockedListener} trong
 * transaction khóa của identity: {@code MANDATORY}, không bắt lỗi — lỗi ở đây rollback cả lần khóa (convention 07
 * §7.2).
 * <p>
 * Không kiểm lại guard của khóa (identity đã kiểm dưới khóa {@code accounts}) và không lọc theo role: đổi chức vụ được
 * phép khi còn lượt được gán (docs/adr/0031 Q4), nên người bị khóa có thể đang giữ lượt dù không còn là VET /
 * CARETAKER. Lượt {@code WAITING} không đánh dấu (lễ tân gán lại tự do, Visit#2). Mở khóa gỡ cờ của lượt mà người đó
 * lại đủ điều kiện phụ trách ({@link #clearFlagsOf}, docs/adr/0088, trả D042; thay docs/adr/0062 Q5).
 */
@Service
public class VisitReassignFlagService {

    private final VisitRepository visits;
    private final StaffDirectoryApi staffDirectory;
    private final Clock clock;

    public VisitReassignFlagService(VisitRepository visits, StaffDirectoryApi staffDirectory, Clock clock) {
        this.visits = visits;
        this.staffDirectory = staffDirectory;
        this.clock = clock;
    }

    /** Số lượt vừa được đánh dấu; lượt đã đánh dấu từ lần khóa trước không tính lại. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int flagCalledVisitsOf(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return visits.flagNeedsReassign(accountId, now);
    }

    /**
     * Tài khoản#8: gỡ cờ của lượt {@code IN_PROGRESS} người vừa được mở khóa còn phụ trách được — {@code ACTIVE} không
     * khóa, đúng chi nhánh, đúng chức vụ với nhóm dịch vụ (cùng điều kiện với {@code VisitDispatchService.isAssignable},
     * tức cũng là điều kiện để #9 từ chối gán lại, BR-TN-08). Người đã đổi chức vụ / chuyển chi nhánh trong lúc bị
     * khóa giữ cờ để quản lý gán lại. Thứ tự khóa: {@code accounts} (identity, đang giữ) → {@code visits}.
     *
     * @return số lượt vừa được gỡ cờ
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int clearFlagsOf(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        StaffSummary staff = staffDirectory.findStaff(accountId).filter(StaffSummary::active).orElse(null);
        if (staff == null || staff.branchId() == null) {
            return 0;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int cleared = 0;
        for (ServiceGroup group : new ServiceGroup[] {ServiceGroup.MEDICAL, ServiceGroup.GROOMING}) {
            if (VisitDispatchService.requiredRole(group) == staff.role()) {
                cleared += visits.clearNeedsReassign(accountId, staff.branchId(), group.name(), now);
            }
        }
        return cleared;
    }
}
