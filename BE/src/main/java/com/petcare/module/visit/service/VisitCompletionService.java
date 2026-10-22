package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Hoàn tất lượt — visit-v1 #10 (Visit#5, UC48, UC49, UC52; BR-KB-01, 02, BR-SP-06, BR-TN-05; docs/adr/0069). Một
 * transaction ghi, READ COMMITTED: Visit → {@code COMPLETED}, Lịch hẹn#7, Order#4, khóa bệnh án.
 * <ul>
 * <li>Thứ tự: role → lượt (projection, 404) → chi nhánh (403) → khóa {@code visits} → người phụ trách hiện tại (403) →
 * chức vụ khớp nhóm (400 BR-TN-05) → {@code IN_PROGRESS} (409) → ghi → guard BR-KB-02 (400).</li>
 * <li>Thứ tự khóa: {@code visits → appointments → orders}. Order được khóa (và nạp lần đầu) ở
 * {@link VisitOrderApi#markPendingByVisit}, <b>rồi mới</b> đọc dòng dịch vụ: đọc trước sẽ nạp entity cũ và lần
 * {@code markPending} sau ghi đè {@code total_amount} (D045); đọc sau khóa trả D044.</li>
 * <li>Guard chạy sau các lệnh ghi; từ chối thì ném lỗi và rollback toàn bộ. Không bắt exception, không audit.</li>
 * </ul>
 */
@Service
public class VisitCompletionService {

    /** Hợp đồng #10: A07, A08 — người được gán lượt. */
    static final Set<String> COMPLETE_ROLES = Set.of("VET", "CARETAKER");

    private final BranchScope branchScope;
    private final AppointmentApi appointments;
    private final VisitOrderApi visitOrders;
    private final CatalogQueryApi catalog;
    private final VisitRepository visits;
    private final MedicalRecordRepository records;
    private final VisitTransitionHandler transitions;
    private final VisitResponder responder;
    private final Clock clock;

    public VisitCompletionService(BranchScope branchScope, StaffDirectoryApi staffDirectory, AppointmentApi appointments,
            VisitOrderApi visitOrders, CatalogQueryApi catalog, VisitRepository visits,
            MedicalRecordRepository records, VisitTransitionHandler transitions, OrderQueryApi orders, PetQueryApi pets,
            CustomerQueryApi customers, VisitMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.appointments = appointments;
        this.visitOrders = visitOrders;
        this.catalog = catalog;
        this.visits = visits;
        this.records = records;
        this.transitions = transitions;
        this.responder = new VisitResponder(orders, pets, customers, staffDirectory, mapper);
        this.clock = clock;
    }

    @Transactional
    public VisitResponse completeVisit(Long visitId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, COMPLETE_ROLES);
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());

        visits.lockById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        Visit visit = visits.findById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi đọc"));
        if (!principal.accountId().equals(visit.getAssigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.getAssigneeId(),
                    "account:" + principal.accountId());
        }
        if (!VisitDispatchService.requiredRole(visit.getServiceGroup()).name().equals(principal.role())) {
            throw new BusinessRuleViolationException("BR-TN-05",
                    "Chức vụ hiện tại không phụ trách được nhóm dịch vụ của lượt — cần gán lại lượt");
        }
        transitions.validateTransition(visit.getStatus(), VisitStatus.COMPLETED);

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        visit.complete(at);
        visits.saveAndFlush(visit);
        if (visit.getAppointmentId() != null) {
            appointments.completeByVisit(visit.getAppointmentId());
        }
        visitOrders.markPendingByVisit(visitId);

        Optional<MedicalRecord> record = records.findById(visitId);
        if (visit.getServiceGroup() == ServiceGroup.MEDICAL) {
            requireCompletable(visit, record);
        }
        record.ifPresent(r -> {
            r.lock(at);
            records.flush();
        });
        return responder.one(visit);
    }

    /** BR-KB-02, BR-SP-06: có dịch vụ Khám → chẩn đoán; chỉ Tiêm → ≥ 1 mũi; không có dịch vụ nào → từ chối (A16). */
    private void requireCompletable(Visit visit, Optional<MedicalRecord> record) {
        List<Long> serviceIds = visitOrders.findServiceIds(visit.getId());
        List<ServiceInfo> services = serviceIds.isEmpty() ? List.of() : catalog.findServices(serviceIds);
        if (services.size() != serviceIds.stream().distinct().count()) {
            throw new IllegalStateException("Dịch vụ của Order lượt #" + visit.getId() + " thiếu trong danh mục");
        }
        boolean exam = services.stream().anyMatch(s -> s.medicalType() == MedicalType.EXAM);
        boolean vaccine = services.stream().anyMatch(s -> s.medicalType() == MedicalType.VACCINE);
        if (exam) {
            if (record.map(MedicalRecord::getDiagnosis).isEmpty()) {
                throw new BusinessRuleViolationException("BR-KB-02",
                        "Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất");
            }
        } else if (vaccine) {
            if (!visits.hasVaccinationInVisit(visit.getPetId(), visit.getId())) {
                throw new BusinessRuleViolationException("BR-KB-02",
                        "Lượt chỉ có dịch vụ tiêm phải ghi nhận ít nhất 1 mũi tiêm");
            }
        } else {
            throw new BusinessRuleViolationException("BR-KB-02",
                    "Lượt khám/tiêm phải còn ít nhất 1 dịch vụ khám hoặc tiêm");
        }
    }
}
