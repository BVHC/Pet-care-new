package com.petcare.module.inpatient.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.DischargeRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.fsm.AdmissionTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Xuất viện — inpatient-v1 #4 (Nhập viện#2, UC55; BR-LT-14; docs/adr/0149 mục 22–25). Một transaction ghi, READ COMMITTED.
 * Vai trò VET do controller chặn.
 * <ul>
 * <li>Thứ tự lỗi: 400 {@code BR-LT-14} tóm tắt rỗng (chưa chạm DB) → 404 ca → 403 chi nhánh → 403 người phụ trách Visit →
 * 409 trạng thái → 400 {@code BR-LT-14} còn y lệnh {@code ORDERED}.</li>
 * <li>Thứ tự khóa: {@code admissions FOR NO KEY UPDATE} (đọc ca <b>sau</b> khóa) → đếm {@code admission_orders}, không
 * khóa → {@code orders FOR NO KEY UPDATE} ({@link VisitOrderApi#addServiceLine}) → {@code kennels FOR NO KEY UPDATE}
 * ({@link KennelApi#release}, chỉ đường a) → UPDATE {@code admissions}. Hai xuất viện cùng ca: bên sau chờ ở khóa ca rồi
 * thấy {@code DISCHARGED} → 409, không thêm dòng tiền lần hai.</li>
 * <li>Không khóa lượt khám: người phụ trách chỉ đổi khi VET cũ đã bị khóa (BR-TN-08), D080 chấp nhận.</li>
 * <li>Số lượng dòng Order = {@link Admission#billedDays} (ngày VN, gồm hai đầu), đơn giá = {@code unit_price} đã chốt, không
 * tra giá hiện hành (BR-BH-03). {@code DECEASED} chỉ ghi kết cục: không đánh dấu thú mất, không kết thúc lưu trú.</li>
 * <li>Không {@code catch}, không audit, không thông báo (khuôn nhập viện, ADR-0148 mục 8).</li>
 * </ul>
 */
@Service
public class AdmissionDischargeService {

    private static final String RULE = "BR-LT-14";

    private final BranchScope branchScope;
    private final AdmissionRepository admissions;
    private final AdmissionOrderRepository admissionOrders;
    private final AdmissionAssignees assignees;
    private final VisitOrderApi visitOrders;
    private final KennelApi kennels;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final AdmissionTransitionHandler transitions;
    private final AdmissionMapper mapper;
    private final Clock clock;

    public AdmissionDischargeService(BranchScope branchScope, AdmissionRepository admissions,
            AdmissionOrderRepository admissionOrders, AdmissionAssignees assignees, VisitOrderApi visitOrders,
            KennelApi kennels, PetQueryApi pets, CatalogQueryApi catalog, AdmissionTransitionHandler transitions,
            AdmissionMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.admissions = admissions;
        this.admissionOrders = admissionOrders;
        this.assignees = assignees;
        this.visitOrders = visitOrders;
        this.kennels = kennels;
        this.pets = pets;
        this.catalog = catalog;
        this.transitions = transitions;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public AdmissionResponse discharge(Long admissionId, DischargeRequest request) {
        SecurityPrincipal principal = branchScope.current();
        if (request.summary().strip().isEmpty()) {
            throw new BusinessRuleViolationException(RULE, "Vui lòng nhập tóm tắt điều trị");
        }
        // Khóa rồi mới đọc: findById trước sẽ trả bản managed cũ sau khi chờ khóa (thấy ADMITTED dù ca đã xuất viện).
        Admission admission = admissions.findByIdForUpdate(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        assignees.requireAssignee(admission, principal);

        transitions.validateTransition(admission.getStatus(), AdmissionStatus.DISCHARGED);
        long ordered = admissionOrders.countOrderedByAdmissionId(admissionId);
        if (ordered > 0) {
            throw new BusinessRuleViolationException(RULE,
                    "Còn " + ordered + " y lệnh chưa thực hiện hoặc hủy");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LineRef line = visitOrders.addServiceLine(admission.getVisitId(), admission.getServiceId(),
                admission.billedDays(now), admission.getUnitPrice(), principal.accountId());
        if (admission.getKennelId() != null) {
            kennels.release(admission.getKennelId());
        }
        admission.discharge(request.outcome(), request.summary(), principal.accountId(), now, line.orderLineId(),
                ordered);
        admissions.saveAndFlush(admission);
        return respond(admission, now);
    }

    private AdmissionResponse respond(Admission admission, Instant now) {
        PetSummary pet = pets.findPet(admission.getPetId())
                .orElseThrow(() -> corrupt("thú", admission));
        ServiceInfo service = catalog.findService(admission.getServiceId())
                .orElseThrow(() -> corrupt("dịch vụ", admission));
        return mapper.toResponse(admission, pet.name(), service.name(), admission.billedDays(now),
                admission.estimatedAmount(now));
    }

    private static IllegalStateException corrupt(String what, Admission admission) {
        return new IllegalStateException("Thiếu " + what + " của ca nội trú #" + admission.getId());
    }
}
