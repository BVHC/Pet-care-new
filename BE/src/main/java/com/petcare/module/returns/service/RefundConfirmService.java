package com.petcare.module.returns.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.returns.dto.ConfirmRefundRequest;
import com.petcare.module.returns.dto.RefundResponse;
import com.petcare.module.returns.repository.RefundDao;
import com.petcare.module.returns.repository.RefundDao.RefundScope;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.sales.api.CollectMethod;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.PlatformException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RefundConfirmService {

    private final RefundDao refunds;
    private final BranchScope scope;
    private final StaffDirectoryApi staff;
    private final CashierShiftQueryApi shifts;
    private final AuditRecorder audit;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResponse confirm(long refundId, ConfirmRefundRequest request) {
        RefundScope access = refunds.findScope(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("khoản hoàn tiền", refundId));
        scope.check(access.branchId());
        boolean gateway = access.method() == CollectMethod.GATEWAY;
        Role expectedRole = gateway ? Role.BRANCH_MANAGER : Role.RECEPTIONIST;
        long actorId = scope.current().accountId();
        if (!expectedRole.name().equals(scope.current().role())
                || staff.findStaffForShare(actorId).filter(actor -> actor.active() && actor.role() == expectedRole
                        && Long.valueOf(access.branchId()).equals(actor.branchId())).isEmpty()) {
            throw new AccessDeniedScopeException("refund:" + refundId, "account:" + actorId);
        }

        // Ca đóng phải đợi giao dịch hoàn tại quầy commit trước khi cộng số dư của ca.
        Long shiftId = gateway ? null : shifts.lockOpenShiftForShare(actorId)
                .filter(shift -> Long.valueOf(access.branchId()).equals(shift.branchId()))
                .map(OpenShift::shiftId).orElse(null);
        RefundResponse refund = refunds.lock(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("khoản hoàn tiền", refundId));
        String expectedStatus = gateway ? "FAILED" : "REQUESTED";
        if (!expectedStatus.equals(refund.status())) {
            throw new InvalidStateTransitionException("hoàn tiền", refund.status(), "SUCCEEDED");
        }
        if (!gateway && shiftId == null) {
            throw new BusinessRuleViolationException("BR-TG-01", "Bạn chưa mở ca thu ngân tại chi nhánh này");
        }
        String note = request.note() == null ? null : request.note().strip();
        if (gateway && (note == null || note.isEmpty())) {
            throw new MissingManualRefundNote();
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (refund.returnRequestId() != null && !refunds.completeReturn(refund.returnRequestId(), now)) {
            throw new InvalidStateTransitionException("yêu cầu trả hàng (chỉ hoàn từ APPROVED)", null, "REFUNDED");
        }
        refunds.confirm(refundId, actorId, shiftId, now);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "SUCCEEDED");
        after.put("method", refund.method().name());
        after.put("amount", refund.amount());
        after.put("cashierShiftId", shiftId);
        audit.record(AuditEntry.of("REFUND_CONFIRMED").entity("refunds", refundId)
                .before(Map.of("status", refund.status())).after(after).reason(note));
        return new RefundResponse(refundId, refund.code(), refund.returnRequestId(), refund.onlineOrderId(),
                refund.method(), refund.amount(), "SUCCEEDED", refund.attempts(), refund.failureReason(), now,
                refund.createdAt());
    }

    private static final class MissingManualRefundNote extends PlatformException {

        private MissingManualRefundNote() {
            super(ErrorCode.VALIDATION_FAILED, "Cần ghi chú khi xác nhận đã hoàn tiền ngoài hệ thống");
        }
    }
}
