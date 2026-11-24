package com.petcare.module.inpatient.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmitRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.fsm.AdmissionTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.visit.api.VisitApi;
import com.petcare.module.visit.api.VisitApi.TreatmentVisitRef;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.sequence.DocumentCodeGenerator;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Nhập viện — inpatient-v1 #1 (Nhập viện#1, UC55; BR-LT-13, BR-LT-18; docs/adr/0148, docs/adr/0149 mục 16–21). Một
 * transaction ghi, READ COMMITTED (đọc lại sau khi chờ khóa thấy dữ liệu đã commit). Vai trò VET do controller chặn.
 * <ul>
 * <li>Thứ tự lỗi: 400 {@code BR-LT-13} nguồn (chưa chạm DB) → 404 lượt / đặt chỗ → 403 chi nhánh → khóa → 409 thú đã có
 * ca {@code ADMITTED} → 400 {@code BR-LT-13} điều kiện → 404 / 400 dịch vụ → ghi (404 / 400 chuồng do
 * {@link KennelApi} ném ở bước khóa cuối).</li>
 * <li>Đường (a) từ lượt khám: {@code pets FOR SHARE → visits FOR NO KEY UPDATE → kennels → document_counters(admissions)
 * → admissions}. Đường (b) từ lưu trú: {@code pets FOR SHARE → boarding_bookings FOR SHARE → document_counters(visits) →
 * visits → document_counters(ORDER) → orders → document_counters(admissions) → admissions}.</li>
 * <li>Hai lần nhập viện đồng thời cùng thú: bên đến sau chờ ở {@code ux_admissions_pet_admitted} rồi lỗi
 * ({@code DataIntegrityViolationException} → 409 {@code CONCURRENCY_CONFLICT}), rollback trọn.</li>
 * <li>Không {@code catch}, không audit, không thông báo (ADR-0148 mục 8).</li>
 * </ul>
 */
@Service
public class AdmissionAdmitService {

    private static final String RULE = "BR-LT-13";

    private final BranchScope branchScope;
    private final VisitQueryApi visitQueries;
    private final VisitApi visits;
    private final PetQueryApi pets;
    private final BoardingQueryApi boarding;
    private final KennelApi kennels;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final AdmissionRepository admissions;
    private final AdmissionTransitionHandler transitions;
    private final DocumentCodeGenerator codes;
    private final AdmissionMapper mapper;
    private final Clock clock;

    public AdmissionAdmitService(BranchScope branchScope, VisitQueryApi visitQueries, VisitApi visits, PetQueryApi pets,
            BoardingQueryApi boarding, KennelApi kennels, CatalogQueryApi catalog, BranchQueryApi branches,
            AdmissionRepository admissions, AdmissionTransitionHandler transitions, DocumentCodeGenerator codes,
            AdmissionMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.visitQueries = visitQueries;
        this.visits = visits;
        this.pets = pets;
        this.boarding = boarding;
        this.kennels = kennels;
        this.catalog = catalog;
        this.branches = branches;
        this.admissions = admissions;
        this.transitions = transitions;
        this.codes = codes;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public AdmissionResponse admit(AdmitRequest request) {
        SecurityPrincipal principal = branchScope.current();
        boolean fromVisit = request.visitId() != null;
        if (fromVisit == (request.boardingBookingId() != null)) {
            throw new BusinessRuleViolationException(RULE,
                    "Chọn đúng một nguồn nhập viện: lượt khám hoặc đặt chỗ lưu trú");
        }
        if (fromVisit && request.kennelId() == null) {
            throw new BusinessRuleViolationException(RULE, "Nhập viện từ lượt khám phải chọn chuồng điều trị");
        }
        if (!fromVisit && request.kennelId() != null) {
            throw new BusinessRuleViolationException(RULE,
                    "Nhập viện từ lưu trú không chọn chuồng: thú ở nguyên chuồng của đặt chỗ");
        }
        return fromVisit ? admitFromVisit(request, principal) : admitFromBoarding(request, principal);
    }

    /** Đường (a): BR-LT-13a, ADR-0148 mục 2 (thú đang lưu trú phải đi đường b). */
    private AdmissionResponse admitFromVisit(AdmitRequest request, SecurityPrincipal principal) {
        Long visitId = request.visitId();
        // Chỉ lấy petId / branchId (cột không đổi) để phân quyền và khóa đúng thứ tự; trạng thái đọc lại sau khóa.
        VisitSummary ref = visitQueries.findVisit(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(ref.branchId());

        PetSummary pet = pets.findPetForShare(ref.petId())
                .orElseThrow(() -> new IllegalStateException("Thú #" + ref.petId() + " của lượt #" + visitId
                        + " biến mất khi khóa"));
        VisitSummary visit = visits.lockVisitForAdmission(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        requireNoActiveAdmission(pet.petId());

        requireAlive(pet);
        if (visit.status() != VisitQueryApi.VisitStatus.IN_PROGRESS) {
            throw new BusinessRuleViolationException(RULE, "Lượt khám phải đang được thực hiện (IN_PROGRESS)");
        }
        if (!principal.accountId().equals(visit.assigneeId())) {
            throw new BusinessRuleViolationException(RULE, "Chỉ VET đang phụ trách lượt khám mới nhập viện được");
        }
        if (visit.group() != ServiceGroup.MEDICAL) {
            throw new BusinessRuleViolationException(RULE, "Chỉ nhập viện được từ lượt khám / tiêm");
        }
        if (boarding.findCurrentStay(pet.petId()).isPresent()) {
            throw new BusinessRuleViolationException(RULE,
                    "Thú đang lưu trú: nhập viện từ đặt chỗ lưu trú, không cấp thêm chuồng");
        }
        ServiceInfo service = requireInpatientService(request.serviceId(), visit.branchId());
        transitions.validateInitial(AdmissionStatus.ADMITTED);

        kennels.occupyForTreatment(request.kennelId(), visit.branchId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Admission admission = admissions.saveAndFlush(Admission.admit(nextCode(now), visitId, pet.petId(),
                visit.branchId(), null, request.kennelId(), service.serviceId(), service.price(),
                principal.accountId(), now));
        return respond(admission, pet, service, now);
    }

    /** Đường (b): BR-LT-13b, Visit#7 + Order#14; đặt chỗ giữ nguyên trạng thái (BR-LT-17). */
    private AdmissionResponse admitFromBoarding(AdmitRequest request, SecurityPrincipal principal) {
        Long bookingId = request.boardingBookingId();
        StaySummary ref = boarding.findBooking(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        branchScope.check(ref.branchId());

        PetSummary pet = pets.findPetForShare(ref.petId())
                .orElseThrow(() -> new IllegalStateException("Thú #" + ref.petId() + " của đặt chỗ #" + bookingId
                        + " biến mất khi khóa"));
        StaySummary stay = boarding.findBookingForShare(bookingId)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " biến mất khi khóa"));
        requireNoActiveAdmission(pet.petId());

        requireAlive(pet);
        if (stay.status() != StayStatus.CHECKED_IN && stay.status() != StayStatus.OVERDUE) {
            throw new BusinessRuleViolationException(RULE,
                    "Đặt chỗ phải đang giữ thú trong chuồng (CHECKED_IN hoặc OVERDUE)");
        }
        ServiceInfo service = requireInpatientService(request.serviceId(), stay.branchId());
        transitions.validateInitial(AdmissionStatus.ADMITTED);

        TreatmentVisitRef treatment = visits.startTreatmentVisit(pet.petId(), stay.customerId(), stay.branchId(),
                principal.accountId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Admission admission = admissions.saveAndFlush(Admission.admit(nextCode(now), treatment.visitId(), pet.petId(),
                stay.branchId(), bookingId, null, service.serviceId(), service.price(), principal.accountId(), now));
        return respond(admission, pet, service, now);
    }

    /** ADR-0148 mục 6: 409 khi tuần tự; đồng thời thật thì {@code ux_admissions_pet_admitted} chặn ở INSERT. */
    private void requireNoActiveAdmission(Long petId) {
        if (admissions.existsActiveByPetId(petId)) {
            throw new InvalidStateTransitionException(AdmissionTransitionHandler.FSM_NAME
                    + " (thú đã có ca đang điều trị)", null, AdmissionStatus.ADMITTED.name());
        }
    }

    private static void requireAlive(PetSummary pet) {
        if (pet.deceased()) {
            throw new BusinessRuleViolationException(RULE, "Thú cưng đã được đánh dấu đã mất");
        }
    }

    /** BR-LT-13, BR-LT-18; A5 của inpatient-v1: đang kinh doanh và đang bật ở chi nhánh của ca. */
    private ServiceInfo requireInpatientService(Long serviceId, Long branchId) {
        ServiceInfo service = catalog.findService(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("dịch vụ", serviceId));
        if (service.medicalType() != MedicalType.INPATIENT) {
            throw new BusinessRuleViolationException(RULE, "Dịch vụ không phải dịch vụ nội trú (INPATIENT)");
        }
        if (!service.active()) {
            throw new BusinessRuleViolationException(RULE, "Dịch vụ nội trú đã ngừng kinh doanh");
        }
        if (!branches.isServiceEnabled(branchId, serviceId)) {
            throw new BusinessRuleViolationException(RULE, "Dịch vụ nội trú chưa được bật tại chi nhánh");
        }
        return service;
    }

    /** Mã ca sinh <b>sau cùng</b> (docs/adr/0149 mục 5): khóa bộ đếm toàn chuỗi theo tháng giữ ngắn nhất. */
    private String nextCode(Instant now) {
        return codes.next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX,
                LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE));
    }

    private AdmissionResponse respond(Admission admission, PetSummary pet, ServiceInfo service, Instant now) {
        return mapper.toResponse(admission, pet.name(), service.name(), admission.billedDays(now),
                admission.estimatedAmount(now));
    }
}
