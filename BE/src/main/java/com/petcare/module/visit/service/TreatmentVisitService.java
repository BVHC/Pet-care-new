package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.api.VisitApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitQueryMapper;
import com.petcare.module.visit.repository.VisitRepository;

/**
 * Visit#7 + Order#14 (docs/09 §C BR-LT-13b; docs/adr/0149 mục 12) và khóa lượt cho nhập viện từ lượt khám (mục 16).
 * Bên gọi (inpatient) đã kiểm mọi rule dưới khóa; ở đây chỉ kiểm FSM rồi ghi. Không bắt exception nào: lỗi từ callee
 * {@code MANDATORY} đã đánh dấu rollback-only.
 */
@Service
public class TreatmentVisitService implements VisitApi {

    private final VisitCodeGenerator codes;
    private final VisitRepository visits;
    private final VisitOrderApi visitOrders;
    private final VisitTransitionHandler transitions;
    private final VisitQueryMapper summaries;
    private final Clock clock;

    public TreatmentVisitService(VisitCodeGenerator codes, VisitRepository visits, VisitOrderApi visitOrders,
            VisitTransitionHandler transitions, VisitQueryMapper summaries, Clock clock) {
        this.codes = codes;
        this.visits = visits;
        this.visitOrders = visitOrders;
        this.transitions = transitions;
        this.summaries = summaries;
        this.clock = clock;
    }

    /**
     * Khóa bằng câu vô hướng {@code lockById} rồi đọc projection {@code findSummary}: projection luôn đọc DB nên thấy dữ
     * liệu đã commit sau khi chờ khóa, không lấy entity cũ trong persistence context (khuôn {@code VisitCompletionService}).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<VisitQueryApi.VisitSummary> lockVisitForAdmission(Long visitId) {
        if (visitId == null) {
            throw new IllegalArgumentException("visitId là bắt buộc");
        }
        if (visits.lockById(visitId).isEmpty()) {
            return Optional.empty();
        }
        return visits.findSummary(visitId).map(summaries::toSummary);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TreatmentVisitRef startTreatmentVisit(Long petId, Long customerId, Long branchId, Long vetId) {
        if (petId == null || customerId == null || branchId == null || vetId == null) {
            throw new IllegalArgumentException("Visit điều trị cần đủ thú, khách, chi nhánh và VET");
        }
        transitions.validateInitial(VisitStatus.IN_PROGRESS);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Visit visit = visits.saveAndFlush(Visit.startTreatment(codes.nextCode(), branchId, customerId, petId, vetId,
                now));
        Long orderId = visitOrders.openTreatmentOrder(visit.getId(), branchId, customerId, vetId);
        return new TreatmentVisitRef(visit.getId(), orderId);
    }
}
