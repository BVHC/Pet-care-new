package com.petcare.module.inpatient.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.inpatient.dto.CreateObservationRequest;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.mapper.AdmissionObservationMapper;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Ghi chỉ số theo dõi nội trú — inpatient-v1 #9; UC56, BR-LT-16; docs/adr/0149 mục 31–36. Một transaction ghi, READ
 * COMMITTED, không {@code catch}, không audit (ADR-0148 mục 8). Vai trò (VET, CARETAKER) do controller chặn
 * ({@code @PreAuthorize} là hàng rào duy nhất, mục 20). <b>Không</b> đòi người phụ trách Visit: mọi VET / CARETAKER của
 * chi nhánh ghi được (inpatient-v1 §B).
 * <p>
 * <b>Thứ tự lỗi:</b> 404 → 403 chi nhánh → <b>409 trạng thái</b> → 400 {@code BR-LT-16} (khuôn y lệnh, mục 26).
 * <b>Khóa:</b> {@code admissions FOR NO KEY UPDATE} (đọc ca <b>sau</b> khóa) → INSERT {@code admission_observations} →
 * (có cân nặng) {@code pets FOR SHARE} + INSERT {@code weight_records} → INSERT outbox. Khóa ca <b>trước</b> mọi ghi để
 * không lọt mục theo dõi sau xuất viện (ràng buộc của mục 23). Không luồng nào khóa {@code pets} rồi mới khóa
 * {@code admissions}, nên không chu trình.
 */
@Service
public class AdmissionObservationService {

    private static final String RULE = "BR-LT-16";

    private final BranchScope branchScope;
    private final AdmissionRepository admissions;
    private final AdmissionObservationRepository observations;
    private final StaffDirectoryApi staff;
    private final PetApi petApi;
    private final AdmissionObservationNotices notices;
    private final AdmissionObservationMapper mapper;

    public AdmissionObservationService(BranchScope branchScope, AdmissionRepository admissions,
            AdmissionObservationRepository observations, StaffDirectoryApi staff, PetApi petApi,
            AdmissionObservationNotices notices, AdmissionObservationMapper mapper) {
        this.branchScope = branchScope;
        this.admissions = admissions;
        this.observations = observations;
        this.staff = staff;
        this.petApi = petApi;
        this.notices = notices;
        this.mapper = mapper;
    }

    /** inpatient-v1 #9: ghi mục theo dõi; cân nặng vào hồ sơ cân (nguồn {@code VET}, Visit của ca); {@code abnormal} báo. */
    @Transactional
    public ObservationResponse create(Long admissionId, CreateObservationRequest request) {
        SecurityPrincipal principal = branchScope.current();
        // Khóa rồi mới đọc: findById trước sẽ trả bản managed cũ sau khi chờ khóa (thấy ADMITTED dù ca đã xuất viện).
        Admission admission = admissions.findByIdForUpdate(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        if (admission.getStatus() != AdmissionStatus.ADMITTED) {
            throw new InvalidStateTransitionException("ca nội trú (chỉ ghi theo dõi khi đang điều trị)",
                    admission.getStatus().name(), "ghi theo dõi");
        }
        AdmissionObservation.invalidReason(request.temperatureC(), request.heartRate(), request.respiratoryRate(),
                request.weightKg(), request.appetite(), request.note()).ifPresent(reason -> {
                    throw new BusinessRuleViolationException(RULE, reason);
                });
        String recordedByName = staff.findStaff(principal.accountId())
                .orElseThrow(() -> new IllegalStateException("Thiếu nhân viên #" + principal.accountId()
                        + " của phiên đăng nhập")).fullName();

        AdmissionObservation observation = AdmissionObservation.record(admission, principal.accountId(),
                request.temperatureC(), request.heartRate(), request.respiratoryRate(), request.weightKg(),
                request.appetite(), request.note(), request.abnormal());
        observations.saveAndFlush(observation);
        // created_at đã lưu (µs): entity giữ nano giây, response #9 phải bằng giá trị #10 trả về (mục 36).
        Instant createdAt = observations.findCreatedAt(observation.getId());

        if (observation.getWeightKg() != null) {
            petApi.recordWeight(admission.getPetId(), observation.getWeightKg(), WeightSource.VET,
                    admission.getVisitId(), null, principal.accountId());
        }
        if (observation.isAbnormal()) {
            notices.enqueue(admission, createdAt, principal.accountId());
        }
        return mapper.toResponse(observation, recordedByName, createdAt);
    }
}
