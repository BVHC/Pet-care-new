package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.CloseShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.ShiftAccessRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chốt ca thu ngân (sales-v1 #18, UC71; Ca thu ngân#3; BR-TG-05; docs/adr/0064). Một {@code @Transactional}, READ
 * COMMITTED. Thứ tự: phân quyền trên projection không khóa (không phải chủ ca → 403, kể cả khác chi nhánh; không có →
 * 404) → khóa dòng ca → không {@code OPEN} 409 → tổng phiếu thu <b>sau khóa</b> → ghi. Thu tiền giữ ca {@code FOR SHARE}
 * nên chốt chờ phiếu thu đang chạy rồi mới tính tổng; thu tiền đến sau khi ca đã chốt bị từ chối BR-TG-01.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashierShiftCloseService {

    private final CashierShiftRepository shifts;
    private final ShiftTotalsReader totals;
    private final CashierShiftTransitionHandler fsm;
    private final CashierShiftAssembler assembler;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional
    public CashierShiftResponse closeCashierShift(Long shiftId, CloseShiftRequest request) {
        Long me = branchScope.current().accountId();
        ShiftAccessRow access = shifts.findAccessRowById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("ca thu ngân", shiftId));
        if (!me.equals(access.getCashierId())) {
            throw new AccessDeniedScopeException("cashierShift:" + shiftId, "cashier:" + me);
        }
        CashierShift shift = shifts.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("ca thu ngân", shiftId));
        if (shift.getStatus() != CashierShiftStatus.OPEN) {
            throw new InvalidStateTransitionException("ca thu ngân", shift.getStatus().name(),
                    CashierShiftStatus.CLOSED.name());
        }
        fsm.validateTransition(shift.getStatus(), CashierShiftStatus.CLOSED);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        shift.closeByCashier(request.countedCash(), totals.read(shiftId), now);
        shifts.saveAndFlush(shift);
        log.info("CASHIER_SHIFT_CLOSED shiftId={} cashierId={} expectedCash={} expectedTransfer={} countedCash={}",
                shiftId, me, shift.getExpectedCash(), shift.getExpectedTransfer(), shift.getCountedCash());
        return assembler.toResponse(shift);
    }
}
