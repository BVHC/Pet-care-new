package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.dto.CancelVisitRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Tiến trình lượt — visit-v1 #8 gọi lượt (Visit#3, UC46, BR-TN-05, 09) và #11 hủy lượt khách bỏ về (Visit#6, UC45,
 * BR-TN-07); docs/adr/0062. Mỗi command một transaction ghi, READ COMMITTED.
 * <ul>
 * <li>Thứ tự (docs/api/00-method.md L70): role → lượt tồn tại (projection, 404) → chi nhánh của lượt (403) → khóa →
 * người gọi là người được gán (#8, 403 chung — Q1) → guard 400 → 409. Dữ liệu có thể đổi đồng thời (người được gán,
 * trạng thái, tài khoản người gọi) chỉ đọc sau khóa.</li>
 * <li>Thứ tự khóa (docs/adr/0058 mục 5): #8 {@code accounts FOR SHARE} (chính người gọi, Q4) → {@code visits}; #11
 * {@code visits} → {@code appointments} ({@link AppointmentApi#cancelByVisit}) → {@code orders}
 * ({@link VisitOrderApi#cancelByVisit}).</li>
 * <li>Không bắt exception: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only. Không audit (BR-QT-15 không liệt
 * kê, 08 §8.3), không thông báo, không ghi {@code visit_assignments}.</li>
 * <li>Gọi lại / hủy lại lượt đã chuyển → 409 (như xác định cấp cứu lần hai, docs/adr/0061).</li>
 * </ul>
 */
@Service
public class VisitProgressService {

    /** Hợp đồng #8: A07, A08 — người được gán lượt (UC46; 03 #3). */
    static final Set<String> CALL_ROLES = Set.of("VET", "CARETAKER");

    /** Hợp đồng #11: A06 (UC45; 03 #6; BR-TN-07). */
    static final Set<String> CANCEL_ROLES = Set.of("RECEPTIONIST");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final AppointmentApi appointments;
    private final VisitOrderApi visitOrders;
    private final VisitRepository visits;
    private final VisitTransitionHandler transitions;
    private final VisitResponder responder;
    private final Clock clock;

    public VisitProgressService(BranchScope branchScope, StaffDirectoryApi staffDirectory, AppointmentApi appointments,
            VisitOrderApi visitOrders, VisitRepository visits, VisitTransitionHandler transitions, OrderQueryApi orders,
            PetQueryApi pets, CustomerQueryApi customers, VisitMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.appointments = appointments;
        this.visitOrders = visitOrders;
        this.visits = visits;
        this.transitions = transitions;
        this.responder = new VisitResponder(orders, pets, customers, staffDirectory, mapper);
        this.clock = clock;
    }

    /**
     * Visit#3 — gọi lượt. Chỉ người được gán gọi được (BR-TN-05, 09: lượt chưa gán cũng là "không thuộc về mình").
     * Người gọi được kiểm lại dưới khóa tài khoản của chính mình: vừa bị khóa / đổi chức vụ thì 400 BR-TN-05, và lần khóa
     * tài khoản chạy song song phải chờ lượt này commit để đánh dấu cần gán lại (docs/adr/0062 Q4).
     */
    @Transactional
    public VisitResponse callVisit(Long visitId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, CALL_ROLES);
        SummaryRow summary = findVisit(visitId);
        branchScope.check(summary.branchId());

        Optional<StaffSummary> caller = staffDirectory.findStaffForShare(principal.accountId());
        Visit visit = lockAndLoad(visitId);
        if (!principal.accountId().equals(visit.getAssigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.getAssigneeId(),
                    "account:" + principal.accountId());
        }
        VisitDispatchService.requireAssignable(caller, visit);
        // Tường minh, không qua FSM: cạnh IN_PROGRESS → IN_PROGRESS có trong map nhưng là #4 (gán lại lượt đã gọi),
        // không phải #3 — chỉ validateTransition thì gọi lần hai sẽ ghi đè called_at thay vì 409.
        if (visit.getStatus() != VisitStatus.WAITING) {
            throw new InvalidStateTransitionException(VisitTransitionHandler.FSM_NAME, visit.getStatus().name(),
                    VisitStatus.IN_PROGRESS.name());
        }
        transitions.validateTransition(VisitStatus.WAITING, VisitStatus.IN_PROGRESS);

        visit.call(clock.instant().truncatedTo(ChronoUnit.MICROS));
        visits.saveAndFlush(visit);
        return responder.one(visit);
    }

    /**
     * Visit#6 — hủy lượt khách bỏ về (BR-TN-07): lượt đã gọi → 409. Lịch hẹn (nếu có) → {@code CANCELLED} không tính
     * hủy muộn (Lịch hẹn#8), Order → {@code CANCELLED} kèm dòng tự sinh (Order#6), cùng transaction. Lý do không bắt
     * buộc (A4).
     */
    @Transactional
    public VisitResponse cancelVisit(Long visitId, CancelVisitRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, CANCEL_ROLES);
        SummaryRow summary = findVisit(visitId);
        branchScope.check(summary.branchId());

        Visit visit = lockAndLoad(visitId);
        transitions.validateTransition(visit.getStatus(), VisitStatus.CANCELLED);

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        visit.cancel(normalizeReason(request), at);
        visits.saveAndFlush(visit);
        if (visit.getAppointmentId() != null) {
            appointments.cancelByVisit(visit.getAppointmentId(), at);
        }
        visitOrders.cancelByVisit(visitId, principal.accountId());
        return responder.one(visit);
    }

    /** Bỏ khoảng trắng hai đầu (cả Unicode); rỗng thì không lưu (khuôn {@code AppointmentChangeService}). */
    static String normalizeReason(CancelVisitRequest request) {
        if (request == null || request.reason() == null) {
            return null;
        }
        String stripped = request.reason().strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private SummaryRow findVisit(Long visitId) {
        return visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
    }

    /** Khóa rồi mới nạp entity; lượt không bị xóa cứng nên mất dòng sau bước đọc là dữ liệu hỏng. */
    private Visit lockAndLoad(Long visitId) {
        visits.lockById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        return visits.findById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi đọc"));
    }
}
