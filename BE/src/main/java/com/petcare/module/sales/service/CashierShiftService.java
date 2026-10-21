package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.OpenShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Mở ca thu ngân (sales-v1 #16, UC71; Ca thu ngân#1 ca thường, #2 ca ngoài giờ; BR-TG-01, 05, BR-CN-05; docs/adr/0064).
 * Một {@code @Transactional}, không {@code noRollbackFor}. Thứ tự: khóa {@code accounts FOR SHARE} của chính lễ tân
 * ({@link StaffDirectoryApi#findStaffForShare}, nghĩa vụ ADR-0031 mục 9: điều chuyển / vô hiệu hóa / khóa song song phải
 * thấy ca mới, hoặc ca mới thấy chi nhánh mới) → ca {@code OPEN} sẵn có 409 → luật giờ mở cửa 400 → INSERT. Hai tab
 * mở ca cùng lúc không tuần tự hóa bằng {@code FOR SHARE}; {@code uq_cashier_shifts_one_open} chặn ca thứ hai (409).
 * Không khóa {@code branches} (như check-in, ADR-0058) và không chặn chi nhánh DRAFT: không có rule nào nói vậy.
 */
@Service
@RequiredArgsConstructor
public class CashierShiftService {

    private final CashierShiftRepository shifts;
    private final StaffDirectoryApi staffDirectory;
    private final BranchQueryApi branches;
    private final CashierShiftTransitionHandler fsm;
    private final CashierShiftAssembler assembler;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional
    public CashierShiftResponse openCashierShift(OpenShiftRequest request) {
        SecurityPrincipal me = branchScope.current();
        StaffSummary staff = staffDirectory.findStaffForShare(me.accountId())
                .filter(s -> s.role() == Role.RECEPTIONIST && s.active() && s.branchId() != null)
                .orElseThrow(() -> new IllegalStateException("Tài khoản #" + me.accountId()
                        + " không phải lễ tân đang hoạt động có chi nhánh"));
        Long branchId = staff.branchId();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime local = LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE);

        if (shifts.findOpenShiftRow(me.accountId()).isPresent()) {
            throw new InvalidStateTransitionException("ca thu ngân (bạn đã có ca đang mở)",
                    CashierShiftStatus.OPEN.name(), CashierShiftStatus.OPEN.name());
        }
        boolean afterHours = Boolean.TRUE.equals(request.afterHours());
        if (afterHours) {
            BranchSummary branch = branches.findBranch(branchId)
                    .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + branchId + " không tồn tại"));
            if (!branch.acceptsAfterHoursEmergency()) {
                throw new BusinessRuleViolationException("BR-CN-05",
                        "Chi nhánh không nhận cấp cứu ngoài giờ nên không mở được ca ngoài giờ");
            }
            if (branches.isOpenAt(branchId, local)) {
                throw new BusinessRuleViolationException("BR-TG-05",
                        "Đang trong giờ mở cửa: hãy mở ca thường");
            }
        } else if (!branches.isOpenAt(branchId, local)) {
            throw new BusinessRuleViolationException("BR-TG-05",
                    "Chỉ mở được ca thường trong giờ mở cửa của chi nhánh");
        }
        fsm.validateInitial(CashierShiftStatus.OPEN);
        CashierShift shift = shifts.saveAndFlush(
                CashierShift.open(branchId, me.accountId(), afterHours, LocalDate.from(local), now));
        return assembler.toResponse(shift);
    }
}
