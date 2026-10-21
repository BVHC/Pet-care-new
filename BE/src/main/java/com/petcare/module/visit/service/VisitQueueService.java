package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.criteria.Predicate;

/**
 * Xem lượt tiếp nhận — visit-v1 #2 hàng đợi chi nhánh, #3 hàng đợi của tôi, #4 chi tiết (UC45, UC46; BR-TN-04, 05, 09;
 * docs/adr/0060). Chỉ đọc, không khóa: mỗi use case một transaction {@code readOnly} + {@code REPEATABLE_READ}, nên lượt,
 * Order và tên hiển thị cùng một snapshot (khuôn docs/adr/0038, 0048).
 * <ul>
 * <li>Role kiểm lại trước mọi lần đọc DB ({@link #requireRole}): role ngoài hợp đồng tới được đây là lỗi lập trình
 * (500), không để SUPER_MANAGER / ADMIN đi qua {@code BranchScope} — với họ {@code resolve} trả {@code null} và
 * {@code check} không chặn. Riêng #4 cho SUPER_MANAGER (toàn chuỗi), để xem lượt cần gán lại khi chi nhánh không còn
 * quản lý hoạt động (visit-v1 A14, docs/adr/0063 D3).</li>
 * <li>#2 luôn ép chi nhánh của người gọi; bộ lọc theo A1 (xem {@link VisitQueueFilter}). #3 ép người phụ trách là chính
 * mình. #4: không có → 404, nhân viên chi nhánh khác → 403.</li>
 * <li>Thứ tự BR-TN-04 sắp trong bộ nhớ ({@link QueueOrder}); tên và {@code orderId} ghép theo lô ({@link VisitResponder}).</li>
 * </ul>
 */
@Service
public class VisitQueueService {

    /** Lượt chưa kết thúc (BR-TN-09): hàng đợi mặc định (#2) và hàng đợi của tôi (#3). */
    static final Set<VisitStatus> UNFINISHED = Set.of(VisitStatus.WAITING, VisitStatus.IN_PROGRESS);

    /** Hợp đồng #2: A05–A08 (yaml; visit-v1 A13 cho CARETAKER). */
    static final Set<String> QUEUE_ROLES = Set.of("BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER");

    /** Hợp đồng #4: A04–A08 (A14: SUPER_MANAGER xem lượt cần gán lại, docs/adr/0063). */
    static final Set<String> DETAIL_ROLES =
            Set.of("SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER");

    /** Hợp đồng #3: A07, A08 (UC46). */
    static final Set<String> MY_QUEUE_ROLES = Set.of("VET", "CARETAKER");

    private final BranchScope branchScope;
    private final VisitRepository visits;
    private final VisitResponder responder;
    private final Clock clock;

    public VisitQueueService(BranchScope branchScope, VisitRepository visits, OrderQueryApi orders, PetQueryApi pets,
            CustomerQueryApi customers, StaffDirectoryApi staff, VisitMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.visits = visits;
        this.responder = new VisitResponder(orders, pets, customers, staff, mapper);
        this.clock = clock;
    }

    /** visit-v1 #2. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<VisitResponse> listQueue(VisitQueueFilter filter) {
        requireRole(branchScope.current(), QUEUE_ROLES);
        Long branchId = branchScope.resolve(null);
        LocalDate today = LocalDate.now(clock);
        List<Visit> rows = new ArrayList<>(visits.findAll(queueSpec(branchId, filter, today)));
        rows.sort(QueueOrder.BR_TN_04);
        return responder.all(rows);
    }

    /** visit-v1 #3. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<VisitResponse> listMyQueue() {
        SecurityPrincipal principal = branchScope.current();
        requireRole(principal, MY_QUEUE_ROLES);
        List<Visit> rows = new ArrayList<>(visits.findByAssigneeIdAndStatusIn(principal.accountId(), UNFINISHED));
        rows.sort(QueueOrder.BR_TN_04);
        return responder.all(rows);
    }

    /** visit-v1 #4. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VisitResponse getVisit(Long visitId) {
        requireRole(branchScope.current(), DETAIL_ROLES);
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(visit.getBranchId());
        return responder.one(visit);
    }

    /** Lớp thứ hai sau {@code @PreAuthorize}; khuôn {@code AppointmentQueryService.viewerOf}. */
    static void requireRole(SecurityPrincipal principal, Set<String> allowed) {
        if (!allowed.contains(principal.role())) {
            throw new IllegalStateException("Role không được xem lượt tiếp nhận: " + principal.role());
        }
    }

    /**
     * Bộ lọc A1 (docs/adr/0060): chỉ ghép điều kiện được gửi. Ngày là ngày check-in theo giờ Việt Nam,
     * {@code [00:00, 00:00 hôm sau)}.
     */
    static Specification<Visit> queueSpec(Long branchId, VisitQueueFilter filter, LocalDate today) {
        if (branchId == null) {
            throw new IllegalStateException("Hàng đợi cần chi nhánh của người gọi");
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("branchId"), branchId));
            VisitStatus status = filter.status();
            LocalDate day = filter.date();
            if (day == null && status != null && !UNFINISHED.contains(status)) {
                day = today;
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else if (filter.date() == null) {
                predicates.add(root.get("status").in(UNFINISHED));
            }
            if (day != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("checkedInAt"), startOf(day)));
                predicates.add(cb.lessThan(root.<Instant>get("checkedInAt"), startOf(day.plusDays(1))));
            }
            if (filter.serviceGroup() != null) {
                predicates.add(cb.equal(root.get("serviceGroup"), filter.serviceGroup().toGroup()));
            }
            if (filter.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assigneeId"), filter.assigneeId()));
            }
            if (filter.needsReassign() != null) {
                predicates.add(cb.equal(root.get("needsReassign"), filter.needsReassign()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
