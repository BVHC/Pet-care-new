package com.petcare.module.visit.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.dto.ReassignRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitAssignment;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitAssignmentRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Gán lại lượt đã gọi — visit-v1 #9 (Visit#4, UC45; BR-TN-05, 08, BR-QT-15; docs/adr/0063). Một transaction ghi, READ
 * COMMITTED: đọc lại tài khoản / lượt sau khi chờ khóa phải thấy dữ liệu vừa commit.
 * <ul>
 * <li>Thứ tự: role → lượt tồn tại (projection, 404) → phạm vi (BRANCH_MANAGER: chi nhánh của lượt; SUPER_MANAGER: chi
 * nhánh không còn BRANCH_MANAGER hoạt động — 403 chung, D2) → khóa → 409 không {@code IN_PROGRESS} → 409 vừa bị gán
 * lại song song → 400 BR-TN-08 người cũ còn đủ điều kiện → 400 BR-TN-05 người mới. 409 trạng thái đứng trước 400
 * BR-TN-08 (D4): guard này chỉ có nghĩa trên lượt đã gọi.</li>
 * <li>Thứ tự khóa (docs/adr/0058 mục 5): {@code accounts FOR SHARE} của người cũ và người mới theo id tăng dần
 * ({@code StaffDirectoryApi.findStaffForShare}) → {@code visits} → {@code orders}
 * ({@link VisitOrderApi#transferLineOwnership}). Người cũ lấy từ projection đọc trước khóa; dưới khóa lượt mà người
 * phụ trách đã khác thì tài khoản vừa khóa không phải của người cũ thật → 409.</li>
 * <li>"Không còn ACTIVE" của BR-TN-08 hiểu là không còn đủ điều kiện phụ trách lượt
 * ({@link VisitDispatchService#isAssignable}: bị khóa / vô hiệu hóa, khác chi nhánh, sai chức vụ — D1, visit-v1 A15).
 * Không xét cờ {@code needs_reassign} (cờ cũ sau mở khóa, nợ D042); gán lại thì gỡ cờ.</li>
 * <li>Ghi audit (BR-TN-08, BR-QT-15) trong transaction; không thông báo. Không bắt exception: lỗi từ callee
 * {@code MANDATORY} đã đánh dấu rollback-only.</li>
 * </ul>
 */
@Service
public class VisitReassignService {

    /** Hợp đồng #9: A05, A04 khi chi nhánh không còn BRANCH_MANAGER hoạt động (UC45; 03 #4; BR-TN-08). */
    static final Set<String> REASSIGN_ROLES = Set.of("BRANCH_MANAGER", "SUPER_MANAGER");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final VisitOrderApi visitOrders;
    private final VisitRepository visits;
    private final VisitAssignmentRepository assignments;
    private final VisitTransitionHandler transitions;
    private final AuditRecorder audit;
    private final VisitResponder responder;

    public VisitReassignService(BranchScope branchScope, StaffDirectoryApi staffDirectory, VisitOrderApi visitOrders,
            VisitRepository visits, VisitAssignmentRepository assignments, VisitTransitionHandler transitions,
            AuditRecorder audit, OrderQueryApi orders, PetQueryApi pets, CustomerQueryApi customers,
            VisitMapper mapper) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.visitOrders = visitOrders;
        this.visits = visits;
        this.assignments = assignments;
        this.transitions = transitions;
        this.audit = audit;
        this.responder = new VisitResponder(orders, pets, customers, staffDirectory, mapper);
    }

    /**
     * Visit#4 — gán lại lượt đã gọi cho {@code request.assigneeId()}. Bệnh án người cũ đã ghi giữ nguyên; các dòng Order
     * người cũ đã thêm chuyển quyền cho người mới (BR-TN-08, BR-BH-02).
     */
    @Transactional
    public VisitResponse reassignCalledVisit(Long visitId, ReassignRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, REASSIGN_ROLES);
        Long newId = Objects.requireNonNull(request.assigneeId(), "assigneeId");
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        requireReassigner(principal, summary.branchId());

        Long oldId = summary.assigneeId();
        Optional<StaffSummary> oldStaff = Optional.empty();
        Optional<StaffSummary> newStaff;
        if (oldId == null || oldId.equals(newId)) {
            newStaff = staffDirectory.findStaffForShare(newId);
            oldStaff = oldId == null ? Optional.empty() : newStaff;
        } else if (oldId < newId) {
            oldStaff = staffDirectory.findStaffForShare(oldId);
            newStaff = staffDirectory.findStaffForShare(newId);
        } else {
            newStaff = staffDirectory.findStaffForShare(newId);
            oldStaff = staffDirectory.findStaffForShare(oldId);
        }
        Visit visit = lockAndLoad(visitId);

        // Tường minh, không chỉ qua FSM: map có WAITING → WAITING (#2), nên validateTransition(status, IN_PROGRESS)
        // sẽ cho lượt WAITING đi qua như gọi lượt (#3) — #9 chỉ dành cho lượt đã gọi.
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw new InvalidStateTransitionException(VisitTransitionHandler.FSM_NAME, visit.getStatus().name(),
                    VisitStatus.IN_PROGRESS.name());
        }
        transitions.validateTransition(VisitStatus.IN_PROGRESS, VisitStatus.IN_PROGRESS);
        if (!Objects.equals(oldId, visit.getAssigneeId())) {
            throw new ConcurrencyConflictException("lượt tiếp nhận", visitId);
        }
        if (VisitDispatchService.isAssignable(oldStaff, visit)) {
            throw new BusinessRuleViolationException("BR-TN-08",
                    "Người phụ trách vẫn đang hoạt động; chỉ gán lại lượt đã gọi khi người phụ trách không còn hoạt động");
        }
        StaffSummary assignee = VisitDispatchService.requireAssignable(newStaff, visit);

        boolean flagged = visit.isNeedsReassign();
        visit.reassignTo(newId);
        visits.saveAndFlush(visit);
        assignments.saveAndFlush(VisitAssignment.of(visitId, oldId, newId, principal.accountId(),
                VisitStatus.IN_PROGRESS, request.reason(), !assignee.online()));
        visitOrders.transferLineOwnership(visitId, oldId, newId);
        audit.record(AuditEntry.of(VisitAuditActions.VISIT_REASSIGNED).entity("visits", visitId)
                .before(snapshot(oldId, flagged))
                .after(snapshot(newId, false))
                .reason(request.reason()));
        return responder.one(visit);
    }

    /**
     * Phạm vi người thực hiện (BR-TN-08): BRANCH_MANAGER của chi nhánh lượt; SUPER_MANAGER chỉ khi chi nhánh không còn
     * BRANCH_MANAGER {@code ACTIVE} không bị khóa — cùng định nghĩa {@code AccountLockService} dùng để báo SUPER_MANAGER.
     * Đọc không khóa (docs/adr/0063 <i>Không đảm bảo</i>).
     */
    private void requireReassigner(SecurityPrincipal principal, Long branchId) {
        if (!"SUPER_MANAGER".equals(principal.role())) {
            branchScope.check(branchId);
            return;
        }
        if (!staffDirectory.findActiveStaffIds(branchId, Role.BRANCH_MANAGER).isEmpty()) {
            throw new AccessDeniedScopeException("branch-without-active-manager:" + branchId,
                    "account:" + principal.accountId());
        }
    }

    /** {@code LinkedHashMap} giữ thứ tự khóa trong JSON audit. */
    private static Map<String, Object> snapshot(Long assigneeId, boolean needsReassign) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assigneeId", assigneeId);
        snapshot.put("needsReassign", needsReassign);
        return snapshot;
    }

    /** Khóa rồi mới nạp entity; lượt không bị xóa cứng nên mất dòng sau bước đọc là dữ liệu hỏng. */
    private Visit lockAndLoad(Long visitId) {
        visits.lockById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        return visits.findById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi đọc"));
    }
}
