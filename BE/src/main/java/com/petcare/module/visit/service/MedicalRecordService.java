package com.petcare.module.visit.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.dto.AddendumRequest;
import com.petcare.module.visit.dto.AddendumResponse;
import com.petcare.module.visit.dto.MedicalRecordResponse;
import com.petcare.module.visit.dto.RecordWeightRequest;
import com.petcare.module.visit.dto.UpdateMedicalRecordRequest;
import com.petcare.module.visit.dto.WeightRecordRefResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordAddendumRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Bệnh án của lượt — visit-v1 #12 xem, #13 ghi, #14 ghi cân nặng, #15 bản bổ sung (UC48, UC53; BR-KB-01, 06, BR-KH-04, BR-TN-08,
 * BR-QT-15; docs/adr/0066, 0067, 0068, 0070).
 * <ul>
 * <li>#13 thứ tự: role → lượt (projection, 404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → khóa
 * {@code pets FOR SHARE} → {@code visits} → người phụ trách hiện tại (403) → {@code IN_PROGRESS} (409) → đọc bệnh án
 * → BR-KB-06 (400) → ghi → audit. Người phụ trách và trạng thái chỉ đọc sau khóa. Một transaction ghi, READ COMMITTED.</li>
 * <li>Khóa thú ngăn đổi loài chạy song song với lần ghi đầu (BR-KH-03, {@code PetService.updatePet}).</li>
 * <li>#14 cùng thứ tự khóa và thứ tự lỗi với #13 ({@code pets FOR SHARE} → {@code visits}), rồi
 * {@code PetApi.recordWeight} (chỉ {@code FOR SHARE}, không nâng khóa). Không audit (BR-QT-15 không liệt kê).</li>
 * <li>Không bắt exception: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only; lỗi audit rollback cả bệnh án.</li>
 * </ul>
 */
@Service
public class MedicalRecordService {

    /** Hợp đồng #12: A07, A08 (CARETAKER chỉ xem — BR-KB-01). */
    static final Set<String> VIEW_ROLES = Set.of("VET", "CARETAKER");

    /** Hợp đồng #13: A07, VET được gán lượt. */
    static final Set<String> WRITE_ROLES = Set.of("VET");

    private final BranchScope branchScope;
    private final PetQueryApi pets;
    private final PetApi petApi;
    private final StaffDirectoryApi staffDirectory;
    private final SystemConfigApi config;
    private final VisitRepository visits;
    private final MedicalRecordRepository records;
    private final MedicalRecordAddendumRepository addenda;
    private final AuditRecorder audit;
    private final MedicalRecordMapper mapper;

    public MedicalRecordService(BranchScope branchScope, PetQueryApi pets, PetApi petApi, StaffDirectoryApi staffDirectory,
            SystemConfigApi config, VisitRepository visits, MedicalRecordRepository records,
            MedicalRecordAddendumRepository addenda, AuditRecorder audit, MedicalRecordMapper mapper) {
        this.branchScope = branchScope;
        this.pets = pets;
        this.petApi = petApi;
        this.staffDirectory = staffDirectory;
        this.config = config;
        this.visits = visits;
        this.records = records;
        this.addenda = addenda;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** visit-v1 #12. Lượt {@code MEDICAL} chưa ghi bệnh án trả khung rỗng; {@code locked} = lượt {@code COMPLETED}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MedicalRecordResponse getMedicalRecord(Long visitId) {
        VisitDispatchService.requireRole(branchScope.current(), VIEW_ROLES);
        SummaryRow summary = findMedicalVisit(visitId);
        boolean locked = summary.status() == VisitStatus.COMPLETED;
        Optional<MedicalRecord> record = records.findById(visitId);
        if (record.isEmpty()) {
            return mapper.toEmptyResponse(visitId, locked);
        }
        List<AddendumResponse> items = locked ? addendaOf(visitId) : List.of();
        return mapper.toResponse(record.get(), locked, items);
    }

    /** visit-v1 #13. */
    @Transactional
    public MedicalRecordResponse updateMedicalRecord(Long visitId, UpdateMedicalRecordRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, WRITE_ROLES);
        SummaryRow summary = findMedicalVisit(visitId);

        pets.findPetForShare(summary.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + summary.petId() + " của lượt biến mất"));
        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);

        Optional<MedicalRecord> existing = records.findById(visitId);
        LocalDate previousDate = existing.map(MedicalRecord::getFollowUpDate).orElse(null);
        if (request.followUpDate() != null && !request.followUpDate().equals(previousDate)) {
            requireFollowUpInRange(visit, request.followUpDate());
        }
        if (existing.isPresent() && existing.get().sameContent(request.examination(), request.diagnosis(),
                request.treatmentPlan(), request.internalNote(), request.followUpDate())) {
            return mapper.toResponse(existing.get(), false, List.of());
        }

        MedicalRecord record = existing.orElseGet(() -> MedicalRecord.of(visitId));
        Map<String, Object> before = existing.map(MedicalRecordService::snapshot).orElse(null);
        record.rewrite(request.examination(), request.diagnosis(), request.treatmentPlan(), request.internalNote(),
                request.followUpDate(), principal.accountId());
        records.saveAndFlush(record);
        audit.record(AuditEntry.of(VisitAuditActions.MEDICAL_RECORD_UPDATED).entity("medical_records", visitId)
                .before(before)
                .after(snapshot(record)));
        return mapper.toResponse(record, false, List.of());
    }

    /** visit-v1 #14. Mỗi lần gọi là một bản ghi cân (A8); thú, nguồn, người ghi lấy từ lượt và phiên. */
    @Transactional
    public WeightRecordRefResponse recordWeight(Long visitId, RecordWeightRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, WRITE_ROLES);
        SummaryRow summary = findMedicalVisit(visitId);

        pets.findPetForShare(summary.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + summary.petId() + " của lượt biến mất"));
        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);

        RecordedWeight recorded = petApi.recordWeight(summary.petId(), request.weightKg(), WeightSource.VET, visitId,
                null, principal.accountId());
        return mapper.toWeightRef(recorded.weightRecordId(), recorded.weightKg(), recorded.measuredAt());
    }

    /**
     * visit-v1 #15 (UC53, BR-KB-01, BR-QT-15; docs/adr/0070). Mọi VET của chi nhánh nơi khám, không cần là người phụ
     * trách (A7); chỉ khi lượt {@code COMPLETED}. Lượt chưa có dòng bệnh án thì tạo dòng rỗng đã khóa
     * ({@code locked_at} = {@code completed_at}) trong cùng transaction. Tên người ghi tra trước mọi lệnh ghi.
     */
    @Transactional
    public AddendumResponse addAddendum(Long visitId, AddendumRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, WRITE_ROLES);
        findMedicalVisit(visitId);

        Visit visit = lockAndLoad(visitId);
        if (visit.getStatus() != VisitStatus.COMPLETED) {
            throw new InvalidStateTransitionException(VisitTransitionHandler.FSM_NAME, visit.getStatus().name(),
                    VisitStatus.COMPLETED.name());
        }
        String authorName = staffDirectory.findStaff(principal.accountId()).map(StaffSummary::fullName)
                .orElseThrow(() -> new IllegalStateException(
                        "Người ghi bản bổ sung #" + principal.accountId() + " không còn trong danh bạ nhân viên"));

        if (records.findById(visitId).isEmpty()) {
            MedicalRecord record = MedicalRecord.of(visitId);
            record.lock(visit.getCompletedAt());
            records.saveAndFlush(record);
        }
        MedicalRecordAddendum addendum = addenda.saveAndFlush(
                MedicalRecordAddendum.of(visitId, request.content(), request.internal(), principal.accountId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("content", addendum.getContent());
        after.put("isInternal", addendum.isInternal());
        audit.record(AuditEntry.of(VisitAuditActions.MEDICAL_RECORD_ADDENDUM_ADDED)
                .entity("medical_record_addenda", addendum.getId()).after(after));
        return mapper.toAddendum(addendum, authorName);
    }

    /**
     * BR-KB-06: sau ngày khám, xa nhất {@code MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS} ngày. Ngày khám = ngày check-in theo giờ
     * Việt Nam (docs/adr/0066 §3); [CFG] đọc lúc ghi (ADR-0057).
     */
    private void requireFollowUpInRange(Visit visit, LocalDate followUpDate) {
        LocalDate examDate = visit.getCheckedInAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        int maxDays = config.getInt(ConfigKey.MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS);
        if (!followUpDate.isAfter(examDate) || followUpDate.isAfter(examDate.plusDays(maxDays))) {
            throw new BusinessRuleViolationException("BR-KB-06",
                    "Ngày tái khám phải sau ngày khám và không quá " + maxDays + " ngày");
        }
    }

    /** Người gọi là người phụ trách hiện tại (403) rồi mới xét trạng thái {@code IN_PROGRESS} (409); gọi sau khi khóa lượt. */
    private static void requireAssigneeInProgress(Visit visit, SecurityPrincipal principal) {
        if (!principal.accountId().equals(visit.getAssigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.getAssigneeId(),
                    "account:" + principal.accountId());
        }
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw new InvalidStateTransitionException(VisitTransitionHandler.FSM_NAME, visit.getStatus().name(),
                    VisitStatus.IN_PROGRESS.name());
        }
    }

    /** Lượt tồn tại (404), thuộc chi nhánh của người gọi (403), nhóm {@code MEDICAL} (404 — GROOMING không có bệnh án). */
    private SummaryRow findMedicalVisit(Long visitId) {
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());
        if (summary.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("bệnh án", visitId);
        }
        return summary;
    }

    /** Bản bổ sung theo thứ tự ghi; tên người ghi tra một lần cho cả lô. */
    private List<AddendumResponse> addendaOf(Long visitId) {
        List<MedicalRecordAddendum> rows = addenda.findByMedicalRecordIdOrderByIdAsc(visitId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = staffDirectory
                .findStaffByIds(rows.stream().map(MedicalRecordAddendum::getCreatedBy).distinct().toList()).stream()
                .collect(Collectors.toMap(StaffSummary::accountId, StaffSummary::fullName));
        return rows.stream()
                .map(row -> mapper.toAddendum(row, Optional.ofNullable(names.get(row.getCreatedBy()))
                        .orElseThrow(() -> new IllegalStateException(
                                "Người ghi bản bổ sung #" + row.getCreatedBy() + " không còn trong danh bạ nhân viên"))))
                .toList();
    }

    /** {@code LinkedHashMap} giữ thứ tự khóa trong JSON audit (docs/adr/0066 §2). */
    private static Map<String, Object> snapshot(MedicalRecord record) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("examination", record.getExamination());
        snapshot.put("diagnosis", record.getDiagnosis());
        snapshot.put("treatmentPlan", record.getTreatmentPlan());
        snapshot.put("internalNote", record.getInternalNote());
        snapshot.put("followUpDate", record.getFollowUpDate() == null ? null : record.getFollowUpDate().toString());
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
