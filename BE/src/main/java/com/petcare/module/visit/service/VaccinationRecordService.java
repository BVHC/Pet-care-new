package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.dto.RecordVaccinationRequest;
import com.petcare.module.visit.dto.RecordVaccinationResultResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.entity.Vaccination;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.mapper.VaccinationOptionsMapper;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.LockedDose;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Ghi nhận mũi tiêm — visit-v1 #19 (UC49, ST06; BR-KB-04, BR-KO-05, BR-TB-03; docs/adr/0074) và xóa mũi ghi nhầm — #20
 * ({@link #delete}, docs/adr/0075). Mỗi use case một transaction ghi, READ COMMITTED.
 * <p>
 * Thứ tự: role → lượt (404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → {@code pets FOR SHARE} → advisory 1004
 * theo thú → {@code visits} → người phụ trách (403) → {@code IN_PROGRESS} (409) → loại vaccine (404, 400 BR-KB-04,
 * 400 BR-SP-07) → mũi thuộc phác đồ (400 BR-KB-04) → sản phẩm {@code FOR SHARE} (400 BR-KB-04) → tồn (400 BR-KB-04) →
 * {@code orders} (dòng VACCINE) → lô FEFO → INSERT mũi → trừ lô → mũi cũ cùng loại → Care Task. Sản phẩm đọc
 * {@code FOR SHARE} <b>trước</b> {@code addVaccineLine} để sản phẩm ngừng kinh doanh chen giữa không thành 500.
 * <p>
 * Không {@code catch}: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only. Không audit (BR-QT-15 không liệt kê).
 */
@Service
public class VaccinationRecordService {

    /** Hợp đồng #19: A07, VET được gán lượt. */
    static final Set<String> ROLES = Set.of("VET");

    /** Namespace advisory lock theo thú, dùng chung với xóa mũi (#20); 1001–1003 đã có chủ (CLAUDE.md). */
    static final int PET_DOSE_LOCK_NAMESPACE = 1004;

    /** Cảnh báo chưa đủ tuổi tối thiểu của mũi đã chọn (BR-KB-04 🆕). */
    static final String UNDER_MIN_AGE = "UNDER_MIN_AGE";

    private final BranchScope branchScope;
    private final VisitRepository visits;
    private final VaccinationRepository vaccinations;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final StockQueryApi stockQuery;
    private final StockApi stock;
    private final VisitOrderApi visitOrders;
    private final CareTaskApi careTasks;
    private final MedicalRecordMapper recordMapper;
    private final VaccinationOptionsMapper mapper;
    private final Clock clock;

    public VaccinationRecordService(BranchScope branchScope, VisitRepository visits, VaccinationRepository vaccinations,
            PetQueryApi pets, CatalogQueryApi catalog, StockQueryApi stockQuery, StockApi stock, VisitOrderApi visitOrders,
            CareTaskApi careTasks, MedicalRecordMapper recordMapper, VaccinationOptionsMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.visits = visits;
        this.vaccinations = vaccinations;
        this.pets = pets;
        this.catalog = catalog;
        this.stockQuery = stockQuery;
        this.stock = stock;
        this.visitOrders = visitOrders;
        this.careTasks = careTasks;
        this.recordMapper = recordMapper;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RecordVaccinationResultResponse record(Long visitId, RecordVaccinationRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, ROLES);
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());
        if (summary.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("lượt khám / tiêm", visitId);
        }

        PetSummary pet = pets.findPetForShare(summary.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + summary.petId() + " của lượt biến mất"));
        vaccinations.acquirePetDoseLock(PET_DOSE_LOCK_NAMESPACE, lockKey(pet.petId()));
        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);

        VaccineTypeInfo type = catalog.findVaccineType(request.vaccineTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("loại vaccine", request.vaccineTypeId()));
        VaccinationOptionService.requireUsableType(type, pet);
        ProtocolDose dose = requireDose(request.protocolId(), type, pet);
        ProductInfo product = requireProduct(request.productId(), type);
        if (stockQuery.availableQuantity(summary.branchId(), product.productId()) < 1) {
            throw new BusinessRuleViolationException("BR-KB-04", "Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh");
        }

        // Sau mọi khóa: now / today là thời điểm ghi, không phải thời điểm bắt đầu chờ khóa.
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        LineRef line = visitOrders.addVaccineLine(visitId, product.productId(), principal.accountId());
        Long lotId = stock.lockFefoLot(summary.branchId(), product.productId(), 1);
        Vaccination saved = vaccinations.saveAndFlush(Vaccination.record(pet.petId(), type.vaccineTypeId(), visitId,
                summary.branchId(), today, dose.protocolId(), dose.doseNumber(), product.productId(), lotId,
                line.orderLineId(), today.plusDays(dose.intervalDays()), principal.accountId()));
        stock.issueForVaccination(lotId, 1, saved.getId(), principal.accountId());

        // BR-TB-03: mũi mới hơn cùng loại vaccine thay thế mũi cũ; nhắc / Care Task tái chủng của mũi cũ bị hủy.
        List<Long> older = vaccinations.findUnsupersededIds(pet.petId(), type.vaccineTypeId(), saved.getId());
        if (!older.isEmpty()) {
            vaccinations.markSuperseded(older, now);
            careTasks.cancelForRevaccination(older);
        }

        List<String> warnings = VaccinationOptionService.underMinAge(pet.birthDate(), today, dose.minAgeWeeks())
                ? List.of(UNDER_MIN_AGE) : List.of();
        VaccinationRecordResponse record = recordMapper.toVaccinationRecord(saved, type.name(), product.name());
        return mapper.toRecordResult(record, warnings);
    }

    /**
     * Xóa mũi tiêm ghi nhầm — visit-v1 #20 (BR-KB-04; docs/adr/0075). Một transaction ghi, READ COMMITTED (các lần đọc sau
     * khóa phải thấy dữ liệu mới nhất).
     * <p>
     * Thứ tự: role → lượt (404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → {@code pets FOR SHARE} → advisory 1004
     * theo thú (dùng chung với #19) → {@code visits} → người phụ trách (403) → {@code IN_PROGRESS} (409) → mũi của lượt
     * {@code FOR UPDATE} (404) → DELETE mũi → xóa dòng Order VACCINE → hoàn kho đúng lô → mũi cũ mới nhất hết bị thay thế.
     * Không {@code catch}; không audit (BR-QT-15 không liệt kê); Care Task đã hủy của mũi cũ không mở lại (CANCELLED là cuối).
     */
    @Transactional
    public void delete(Long visitId, Long vaccinationId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, ROLES);
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());
        if (summary.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("lượt khám / tiêm", visitId);
        }

        PetSummary pet = pets.findPetForShare(summary.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + summary.petId() + " của lượt biến mất"));
        vaccinations.acquirePetDoseLock(PET_DOSE_LOCK_NAMESPACE, lockKey(pet.petId()));
        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);
        LockedDose dose = vaccinations.lockDoseInVisit(vaccinationId, visitId)
                .orElseThrow(() -> new ResourceNotFoundException("mũi tiêm", vaccinationId));

        // FK vaccinations.order_line_id RESTRICT: xóa mũi trước, dòng Order sau (06 §4.1); Order khóa trước lô (ADR-0044).
        if (vaccinations.deleteDose(dose.getId()) != 1) {
            throw new IllegalStateException("Mũi tiêm #" + dose.getId() + " đã khóa nhưng không xóa được");
        }
        visitOrders.removeVaccineLine(dose.getOrderLineId());
        stock.revertVaccination(dose.getStockLotId(), 1, dose.getId(), principal.accountId());

        // BR-TB-03 ngược chiều: chỉ mũi chưa bị thay thế mới để lại mũi cũ bị nó thay thế; mũi đã bị thay thế thì không đổi gì.
        if (!dose.getSuperseded()) {
            vaccinations.restoreLatest(dose.getPetId(), dose.getVaccineTypeId(), clock.instant());
        }
    }

    /** Cùng công thức {@code AppointmentLocks.key}; chép vì {@code visit} không import service của appointment. */
    static int lockKey(long id) {
        return (int) (id % Integer.MAX_VALUE);
    }

    /** Mũi phải thuộc phác đồ đang dùng của đúng loài và đúng loại đã chọn (BR-KB-04, BR-SP-02). */
    private ProtocolDose requireDose(Long protocolId, VaccineTypeInfo type, PetSummary pet) {
        return catalog.findProtocolDose(protocolId)
                .filter(d -> d.active() && d.vaccineTypeId().equals(type.vaccineTypeId()) && d.species() == pet.species())
                .orElseThrow(() -> new BusinessRuleViolationException("BR-KB-04",
                        "Mũi tiêm không thuộc phác đồ đang dùng của loại vaccine cho loài này"));
    }

    /** Khóa chia sẻ (không bao giờ {@code findProduct}): ngừng kinh doanh song song phải chờ, không thành 500 ở sales. */
    private ProductInfo requireProduct(Long productId, VaccineTypeInfo type) {
        return catalog.findProductForShare(productId)
                .filter(p -> p.active() && p.type() == ProductType.VACCINE && type.vaccineTypeId().equals(p.vaccineTypeId()))
                .orElseThrow(() -> new BusinessRuleViolationException("BR-KB-04",
                        "Sản phẩm không phải vaccine đang kinh doanh của loại đã chọn"));
    }

    /** Người gọi là người phụ trách hiện tại (403) rồi mới xét {@code IN_PROGRESS} (409); gọi sau khi khóa lượt. */
    private static void requireAssigneeInProgress(Visit visit, SecurityPrincipal principal) {
        if (!principal.accountId().equals(visit.getAssigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.getAssigneeId(), "account:" + principal.accountId());
        }
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw new InvalidStateTransitionException(VisitTransitionHandler.FSM_NAME, visit.getStatus().name(),
                    VisitStatus.IN_PROGRESS.name());
        }
    }

    /** Khóa rồi mới nạp entity; lượt không bị xóa cứng nên mất dòng sau bước đọc là dữ liệu hỏng. */
    private Visit lockAndLoad(Long visitId) {
        visits.lockById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi khóa"));
        return visits.findById(visitId)
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + visitId + " biến mất khi đọc"));
    }
}
