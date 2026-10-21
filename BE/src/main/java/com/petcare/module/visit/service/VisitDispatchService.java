package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.dto.AssignRequest;
import com.petcare.module.visit.dto.AssignResult;
import com.petcare.module.visit.dto.MoveVisitRequest;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitAssignment;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitAssignmentRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Điều phối hàng đợi — visit-v1 #5 gán / gán lại lượt chưa gọi (Visit#2), #6 sắp lại, #7 xác định ca cấp cứu (UC45,
 * UC47; BR-TN-04, 05, 06, 08; docs/adr/0061). Mỗi command một transaction ghi, READ COMMITTED.
 * <ul>
 * <li>Thứ tự (docs/api/00-method.md L70): role → lượt tồn tại (projection, 404) → chi nhánh của lượt (403) → khóa →
 * guard 400 → 409. {@code branch_id} không đổi sau khi tạo nên được đọc trước khóa; mọi dữ liệu có thể đổi đồng thời
 * (nhân viên, trạng thái / hạng / khóa sắp xếp của lượt) chỉ đọc sau khóa.</li>
 * <li>Thứ tự khóa (docs/adr/0058 mục 5): {@code accounts FOR SHARE} (người được gán, qua
 * {@code StaffDirectoryApi.findStaffForShare}) → {@code visits} → {@code orders} (sales). Sắp lại khóa mọi lượt
 * {@code WAITING} của chi nhánh trong một câu theo id.</li>
 * <li>Không bắt exception: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only. Không audit (08 §8.3), không
 * thông báo; lịch sử gán là {@code visit_assignments}.</li>
 * <li>Gán đúng người đang được gán / sắp vào đúng chỗ hiện tại → trả kết quả hiện tại, không ghi gì (Q4).</li>
 * </ul>
 */
@Service
public class VisitDispatchService {

    /** Hợp đồng #5, #6: A06 (UC45; 03 #2; BR-TN-04 "lễ tân được sắp lại"). */
    static final Set<String> DISPATCH_ROLES = Set.of("RECEPTIONIST");

    /** Hợp đồng #7: A06, A07 (UC47). */
    static final Set<String> EMERGENCY_ROLES = Set.of("RECEPTIONIST", "VET");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final VisitOrderApi visitOrders;
    private final VisitRepository visits;
    private final VisitAssignmentRepository assignments;
    private final VisitTransitionHandler transitions;
    private final VisitMapper mapper;
    private final VisitResponder responder;
    private final Clock clock;

    public VisitDispatchService(BranchScope branchScope, StaffDirectoryApi staffDirectory, VisitOrderApi visitOrders,
            VisitRepository visits, VisitAssignmentRepository assignments, VisitTransitionHandler transitions,
            OrderQueryApi orders, PetQueryApi pets, CustomerQueryApi customers, VisitMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.visitOrders = visitOrders;
        this.visits = visits;
        this.assignments = assignments;
        this.transitions = transitions;
        this.mapper = mapper;
        this.responder = new VisitResponder(orders, pets, customers, staffDirectory, mapper);
        this.clock = clock;
    }

    /**
     * Visit#2 — gán / gán lại lượt chưa gọi (BR-TN-05, 06, 08). Dòng tự sinh và các dòng người cũ đã thêm chuyển cho
     * người mới (BR-TN-01; docs/adr/0061 Q2). Người được gán offline: cảnh báo, không chặn.
     */
    @Transactional
    public AssignResult assignVisit(Long visitId, AssignRequest request) {
        SecurityPrincipal principal = branchScope.current();
        requireRole(principal, DISPATCH_ROLES);
        Long assigneeId = request.assigneeId();
        SummaryRow summary = findVisit(visitId);
        branchScope.check(summary.branchId());

        Optional<StaffSummary> found = staffDirectory.findStaffForShare(assigneeId);
        Visit visit = lockAndLoad(visitId);
        StaffSummary assignee = requireAssignable(found, visit);
        transitions.validateTransition(visit.getStatus(), VisitStatus.WAITING);
        boolean offline = !assignee.online();
        if (assigneeId.equals(visit.getAssigneeId())) {
            return mapper.toAssignResult(responder.one(visit), offline);
        }

        Long previous = visit.getAssigneeId();
        visit.assignTo(assigneeId);
        visits.saveAndFlush(visit);
        assignments.saveAndFlush(VisitAssignment.of(visitId, previous, assigneeId, principal.accountId(),
                VisitStatus.WAITING, null, offline));
        visitOrders.setAutoLineOwner(visitId, assigneeId);
        if (previous != null) {
            visitOrders.transferLineOwnership(visitId, previous, assigneeId);
        }
        return mapper.toAssignResult(responder.one(visit), offline);
    }

    /**
     * Sắp lại hàng đợi (BR-TN-04, A3): đặt lượt ngay trước {@code placeBeforeVisitId}, nhận hạng của lượt đó; không vượt
     * ranh giới ca cấp cứu (docs/adr/0061 Q1). Trả hàng đợi chi nhánh sau khi sắp (như visit-v1 #2 không lọc).
     */
    @Transactional
    public List<VisitResponse> moveVisitInQueue(Long visitId, MoveVisitRequest request) {
        requireRole(branchScope.current(), DISPATCH_ROLES);
        SummaryRow source = findVisit(visitId);
        branchScope.check(source.branchId());
        Long targetId = request.placeBeforeVisitId();
        if (targetId.equals(visitId)) {
            throw new BusinessRuleViolationException("BR-TN-04", "Không đặt lượt trước chính nó được");
        }
        SummaryRow targetRow = findVisit(targetId);
        if (!targetRow.branchId().equals(source.branchId())) {
            throw new BusinessRuleViolationException("BR-TN-04", "Lượt đích thuộc chi nhánh khác");
        }

        Set<Long> locked = new LinkedHashSet<>(visits.lockWaitingIdsOfBranch(source.branchId()));
        // Lượt không nằm trong tập (đã được gọi, hoặc vừa check-in sau câu khóa tập): khóa riêng trước khi nạp entity,
        // để mọi entity của use case đều đọc sau khóa.
        for (Long id : List.of(visitId, targetId)) {
            if (locked.add(id)) {
                visits.lockById(id).orElseThrow(
                        () -> new IllegalStateException("Lượt tiếp nhận #" + id + " biến mất khi khóa"));
            }
        }
        Visit moved = load(visitId);
        Visit target = load(targetId);
        if ((moved.getPriorityClass() == PriorityClass.EMERGENCY)
                != (target.getPriorityClass() == PriorityClass.EMERGENCY)) {
            throw new BusinessRuleViolationException("BR-TN-04",
                    "Ca cấp cứu chỉ sắp trong nhóm cấp cứu; muốn đưa lượt lên nhóm cấp cứu hãy xác định ca cấp cứu");
        }
        transitions.validateTransition(moved.getStatus(), VisitStatus.WAITING);
        transitions.validateTransition(target.getStatus(), VisitStatus.WAITING);

        List<QueueReorder.Change> changes = QueueReorder.placeBefore(visits.findAllById(locked), moved, target);
        for (QueueReorder.Change change : changes) {
            change.visit().reorder(change.priorityClass(), change.queueSortKey());
        }
        if (!changes.isEmpty()) {
            visits.flush();
        }
        return queueOf(source.branchId());
    }

    /** Xác định ca cấp cứu sau khi tiếp nhận (UC47, A2): hạng {@code EMERGENCY}, khóa = lúc đánh dấu (Q3). */
    @Transactional
    public VisitResponse markVisitEmergency(Long visitId) {
        requireRole(branchScope.current(), EMERGENCY_ROLES);
        SummaryRow summary = findVisit(visitId);
        branchScope.check(summary.branchId());

        Visit visit = lockAndLoad(visitId);
        transitions.validateTransition(visit.getStatus(), VisitStatus.WAITING);
        if (visit.getPriorityClass() == PriorityClass.EMERGENCY) {
            throw new InvalidStateTransitionException("hạng ưu tiên của lượt", PriorityClass.EMERGENCY.name(),
                    PriorityClass.EMERGENCY.name());
        }
        visit.markEmergency(clock.instant().truncatedTo(ChronoUnit.MICROS));
        visits.saveAndFlush(visit);
        return responder.one(visit);
    }

    /** Lớp thứ hai sau {@code @PreAuthorize} (khuôn {@code VisitQueueService.requireRole}, docs/adr/0060 §1). */
    static void requireRole(SecurityPrincipal principal, Set<String> allowed) {
        if (!allowed.contains(principal.role())) {
            throw new IllegalStateException("Role không được điều phối hàng đợi: " + principal.role());
        }
    }

    /**
     * BR-TN-05 dạng điều kiện, cùng định nghĩa với {@link #requireAssignable}: còn được phụ trách lượt này. Gán lại lượt đã
     * gọi (Visit#4) chỉ cho khi người phụ trách hiện tại không còn thỏa điều kiện này (docs/adr/0063 D1).
     */
    static boolean isAssignable(Optional<StaffSummary> found, Visit visit) {
        return found.filter(s -> s.active() && visit.getBranchId().equals(s.branchId())
                && s.role() == requiredRole(visit.getServiceGroup())).isPresent();
    }

    /** BR-TN-05 (03 #2): nhân viên đang hoạt động của chi nhánh, đúng chức vụ theo nhóm dịch vụ — đọc dưới khóa. */
    static StaffSummary requireAssignable(Optional<StaffSummary> found, Visit visit) {
        StaffSummary staff = found
                .filter(s -> s.active() && visit.getBranchId().equals(s.branchId()))
                .orElseThrow(() -> new BusinessRuleViolationException("BR-TN-05",
                        "Nhân viên không hoạt động tại chi nhánh này"));
        if (staff.role() != requiredRole(visit.getServiceGroup())) {
            throw new BusinessRuleViolationException("BR-TN-05",
                    "Lượt Khám/Tiêm phải gán bác sĩ thú y; lượt Thẩm mỹ phải gán nhân viên chăm sóc");
        }
        return staff;
    }

    /** BR-TN-05, BR-SP-06: gán theo nhóm. {@code visits.service_group} chỉ có hai nhóm này (erd §6). */
    static Role requiredRole(ServiceGroup group) {
        return switch (group) {
            case MEDICAL -> Role.VET;
            case GROOMING -> Role.CARETAKER;
            default -> throw new IllegalStateException("Lượt tiếp nhận không có nhóm " + group);
        };
    }

    private SummaryRow findVisit(Long visitId) {
        return visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
    }

    /** Khóa rồi mới nạp entity; lượt không bị xóa cứng nên mất dòng sau bước đọc là dữ liệu hỏng. */
    private Visit lockAndLoad(Long visitId) {
        visits.lockById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        return load(visitId);
    }

    private Visit load(Long visitId) {
        return visits.findById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi đọc"));
    }

    private List<VisitResponse> queueOf(Long branchId) {
        VisitQueueFilter everythingUnfinished = new VisitQueueFilter(null, null, null, null, null);
        List<Visit> rows = new ArrayList<>(visits.findAll(
                VisitQueueService.queueSpec(branchId, everythingUnfinished, LocalDate.now(clock))));
        rows.sort(QueueOrder.BR_TN_04);
        return responder.all(rows);
    }
}
