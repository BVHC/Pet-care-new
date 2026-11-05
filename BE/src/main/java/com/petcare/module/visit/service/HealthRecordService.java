package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.visit.dto.AddendumResponse;
import com.petcare.module.visit.dto.HealthRecordResponse;
import com.petcare.module.visit.dto.HealthVisitEntryResponse;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.dto.UpcomingVaccinationResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordAddendumRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.HealthRow;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * visit-v1 #21 — hồ sơ sức khỏe của thú (UC25, UC53; BR-KH-07, BR-KB-01, 04, 06; docs/adr/0108). Chỉ đọc, một
 * transaction {@code REPEATABLE_READ} để lượt, bệnh án, đơn thuốc, bản bổ sung và mũi tiêm cùng một ảnh chụp; không khóa,
 * không audit. Số câu truy vấn không phụ thuộc số lượt: lượt, bệnh án, đơn thuốc (+ tên thuốc), bản bổ sung (+ tên người
 * ghi), mũi tiêm (+ tên loại vaccine, tên sản phẩm), tên chi nhánh.
 * <ul>
 * <li>Khách: phải là chủ hiện tại của thú (chủ cũ sau chuyển chủ 403) và hồ sơ khách không chờ quyết định liên kết
 * (BR-TK-19, 400). Không nhận {@code internalNote} và bản bổ sung nội bộ (BR-KH-07). Thứ tự lỗi: 403 vai trò → 400
 * BR-TK-19 → 404 thú → 403 không phải chủ.</li>
 * <li>Nhân viên (lễ tân, VET, CARETAKER): mọi thú, không giới hạn chi nhánh (hồ sơ theo thú, không theo lượt); nhận đủ ghi
 * chú nội bộ.</li>
 * <li>{@code visits}: chỉ lượt {@code COMPLETED} (bệnh án đã khóa), mọi nhóm dịch vụ và mọi chi nhánh, mới nhất trước.
 * {@code vaccinations}: mọi mũi tại hệ thống. {@code upcomingVaccinations}: mũi chưa bị thay thế.
 * {@code upcomingFollowUpDate}: ngày tái khám nhỏ nhất không trước hôm nay (giờ Việt Nam).</li>
 * </ul>
 */
@Service
public class HealthRecordService {

    /** Hợp đồng #21: A02, A06, A07, A08. */
    static final Set<String> VIEW_ROLES = Set.of("CUSTOMER", "RECEPTIONIST", "VET", "CARETAKER");

    private static final String CUSTOMER = "CUSTOMER";

    private final BranchScope branchScope;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final BranchQueryApi branches;
    private final StaffDirectoryApi staffDirectory;
    private final Clock clock;
    private final VisitRepository visits;
    private final MedicalRecordRepository records;
    private final MedicalRecordAddendumRepository addenda;
    private final VaccinationRecordReader vaccinations;
    private final PrescriptionItemReader prescriptions;
    private final MedicalRecordMapper mapper;

    public HealthRecordService(BranchScope branchScope, PetQueryApi pets, CustomerQueryApi customers,
            BranchQueryApi branches, StaffDirectoryApi staffDirectory, Clock clock, VisitRepository visits,
            MedicalRecordRepository records, MedicalRecordAddendumRepository addenda,
            VaccinationRecordReader vaccinations, PrescriptionItemReader prescriptions, MedicalRecordMapper mapper) {
        this.branchScope = branchScope;
        this.pets = pets;
        this.customers = customers;
        this.branches = branches;
        this.staffDirectory = staffDirectory;
        this.clock = clock;
        this.visits = visits;
        this.records = records;
        this.addenda = addenda;
        this.vaccinations = vaccinations;
        this.prescriptions = prescriptions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HealthRecordResponse getHealthRecord(Long petId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, VIEW_ROLES);
        boolean customer = CUSTOMER.equals(principal.role());
        Long ownCustomerId = customer ? ownCustomerId(principal) : null;
        PetSummary pet = pets.findPet(petId).orElseThrow(() -> new ResourceNotFoundException("thú cưng", petId));
        if (customer && !pet.customerId().equals(ownCustomerId)) {
            throw new AccessDeniedScopeException("customer:" + pet.customerId(), "customer:" + ownCustomerId);
        }

        List<HealthRow> rows = visits.findCompletedByPetId(petId);
        List<Long> visitIds = rows.stream().map(HealthRow::visitId).toList();
        Map<Long, MedicalRecord> recordByVisit = visitIds.isEmpty() ? Map.of()
                : records.findAllById(visitIds).stream()
                        .collect(Collectors.toMap(MedicalRecord::getVisitId, Function.identity()));
        Map<Long, List<PrescriptionItemResponse>> prescribed = prescriptions.forRecords(visitIds);
        Map<Long, List<AddendumResponse>> notes = addendaOf(visitIds, customer);
        Map<Long, String> branchNames = branchNames(rows);

        List<HealthVisitEntryResponse> entries = rows.stream()
                .map(row -> entry(row, recordByVisit.get(row.visitId()), branchNames.get(row.branchId()),
                        prescribed.getOrDefault(row.visitId(), List.of()), notes.getOrDefault(row.visitId(), List.of()),
                        customer))
                .toList();

        List<VaccinationRecordResponse> doses = vaccinations.forPet(petId);
        List<UpcomingVaccinationResponse> upcoming = doses.stream().filter(dose -> !dose.superseded())
                .map(dose -> new UpcomingVaccinationResponse(dose.vaccineTypeId(), dose.vaccineTypeName(),
                        dose.nextDueDate()))
                .sorted(Comparator.comparing(UpcomingVaccinationResponse::nextDueDate)
                        .thenComparing(UpcomingVaccinationResponse::vaccineTypeId))
                .toList();
        LocalDate today = clock.instant().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        LocalDate followUp = recordByVisit.values().stream().map(MedicalRecord::getFollowUpDate)
                .filter(date -> date != null && !date.isBefore(today)).min(Comparator.naturalOrder()).orElse(null);
        return new HealthRecordResponse(petId, entries, doses, upcoming, followUp);
    }

    /** Hồ sơ khách của tài khoản (BR-KH-01); đang chờ quyết định liên kết thì 400 BR-TK-19. */
    private Long ownCustomerId(SecurityPrincipal principal) {
        Long customerId = customers.findCustomerIdByAccountId(principal.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tài khoản khách #" + principal.accountId() + " không có hồ sơ khách (BR-KH-01)"));
        CustomerContact contact = customers.findContact(customerId)
                .orElseThrow(() -> new IllegalStateException("Hồ sơ khách #" + customerId + " biến mất"));
        if (contact.linkDecisionPending()) {
            throw new BusinessRuleViolationException("BR-TK-19",
                    "Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám");
        }
        return customerId;
    }

    /** Bản bổ sung theo thứ tự ghi, nhóm theo lượt; khách bỏ bản nội bộ. Tên người ghi tra một lần cho cả lô. */
    private Map<Long, List<AddendumResponse>> addendaOf(List<Long> visitIds, boolean customer) {
        if (visitIds.isEmpty()) {
            return Map.of();
        }
        List<MedicalRecordAddendum> rows = addenda.findByMedicalRecordIdInOrderByIdAsc(visitIds).stream()
                .filter(row -> !customer || !row.isInternal()).toList();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = staffDirectory
                .findStaffByIds(rows.stream().map(MedicalRecordAddendum::getCreatedBy).distinct().toList()).stream()
                .collect(Collectors.toMap(StaffSummary::accountId, StaffSummary::fullName));
        Map<Long, List<AddendumResponse>> byVisit = new LinkedHashMap<>();
        for (MedicalRecordAddendum row : rows) {
            String name = Optional.ofNullable(names.get(row.getCreatedBy())).orElseThrow(
                    () -> new IllegalStateException(
                            "Người ghi bản bổ sung #" + row.getCreatedBy() + " không còn trong danh bạ nhân viên"));
            byVisit.computeIfAbsent(row.getMedicalRecordId(), id -> new ArrayList<>())
                    .add(mapper.toAddendum(row, name));
        }
        return byVisit;
    }

    private Map<Long, String> branchNames(List<HealthRow> rows) {
        Map<Long, String> names = new HashMap<>();
        for (Long branchId : rows.stream().map(HealthRow::branchId).distinct().toList()) {
            names.put(branchId, branches.findBranch(branchId).map(BranchSummary::name)
                    .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + branchId + " của lượt không còn")));
        }
        return names;
    }

    /** Lượt không có bệnh án (GROOMING, hoặc MEDICAL hoàn tất mà chưa ghi gì) để trống các trường khám. */
    private static HealthVisitEntryResponse entry(HealthRow row, MedicalRecord record, String branchName,
            List<PrescriptionItemResponse> prescribed, List<AddendumResponse> notes, boolean customer) {
        LocalDate visitDate = row.checkedInAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        if (record == null) {
            return new HealthVisitEntryResponse(row.visitId(), visitDate, branchName, row.group(), null, null, null,
                    null, null, List.of(), List.of());
        }
        return new HealthVisitEntryResponse(row.visitId(), visitDate, branchName, row.group(), record.getExamination(),
                record.getDiagnosis(), record.getTreatmentPlan(), customer ? null : record.getInternalNote(),
                record.getFollowUpDate(), prescribed, notes);
    }
}
