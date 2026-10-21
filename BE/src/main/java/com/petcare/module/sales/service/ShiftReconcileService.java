package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.ReconcileShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.ShiftAccessRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Đối soát ca thu ngân (sales-v1 #21, UC72; Ca thu ngân#6; BR-TG-04, 05, BR-QT-15; docs/adr/0064). BRANCH_MANAGER của
 * chi nhánh (vai trò kiểm ở controller). Một {@code @Transactional}: projection → 404 → 403 chi nhánh → khóa dòng ca →
 * không {@code CLOSED} 409 → ca tự chốt chưa có số thực đếm mà request không gửi 400 BR-TG-05 → FSM → ghi → audit. Gửi
 * {@code countedCash} thì ghi đè số lễ tân đã nhập; audit giữ số cũ. {@code difference = thực đếm − tiền mặt hệ thống}.
 */
@Service
@RequiredArgsConstructor
public class ShiftReconcileService {

    private final CashierShiftRepository shifts;
    private final CashierShiftTransitionHandler fsm;
    private final AuditRecorder audit;
    private final CashierShiftAssembler assembler;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional
    public CashierShiftResponse reconcileCashierShift(Long shiftId, ReconcileShiftRequest request) {
        ShiftAccessRow access = shifts.findAccessRowById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("ca thu ngân", shiftId));
        branchScope.check(access.getBranchId());
        CashierShift shift = shifts.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("ca thu ngân", shiftId));
        if (shift.getStatus() != CashierShiftStatus.CLOSED) {
            throw new InvalidStateTransitionException("ca thu ngân (chỉ đối soát được ca đã chốt)",
                    shift.getStatus().name(), CashierShiftStatus.RECONCILED.name());
        }
        if (request.countedCash() == null && shift.getCountedCash() == null) {
            throw new BusinessRuleViolationException("BR-TG-05",
                    "Ca tự chốt chưa có số tiền thực đếm: vui lòng nhập số tiền khi đối soát");
        }
        fsm.validateTransition(shift.getStatus(), CashierShiftStatus.RECONCILED);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", shift.getStatus().name());
        before.put("countedCash", shift.getCountedCash());
        before.put("autoClosed", shift.isAutoClosed());
        String note = request.note().strip();
        Long manager = branchScope.current().accountId();
        shift.reconcile(request.countedCash(), note, manager, Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        shifts.saveAndFlush(shift);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", shift.getStatus().name());
        after.put("countedCash", shift.getCountedCash());
        after.put("expectedCash", shift.getExpectedCash());
        after.put("difference", shift.getDifference());
        audit.record(AuditEntry.of(SalesAuditActions.CASHIER_SHIFT_RECONCILED).entity("cashier_shifts", shiftId)
                .before(before).after(after).reason(note));
        return assembler.toResponse(shift);
    }
}
