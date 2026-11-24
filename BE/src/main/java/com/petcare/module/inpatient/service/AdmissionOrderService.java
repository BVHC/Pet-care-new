package com.petcare.module.inpatient.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inpatient.dto.AdmissionOrderReasonRequest;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.dto.CompleteAdmissionOrderRequest;
import com.petcare.module.inpatient.dto.CreateAdmissionOrderRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.fsm.AdmissionOrderTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionOrderMapper;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.module.visit.api.PrescriptionApi.PrescriptionItemRef;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Y lệnh nội trú — inpatient-v1 #5 (ra), #7 (thực hiện), #8 (hủy); UC56, BR-LT-15; docs/adr/0149 mục 26–30. Mỗi hàm một
 * transaction ghi, READ COMMITTED, không {@code catch}, không audit, không thông báo (ADR-0148 mục 8). Vai trò do
 * controller chặn ({@code @PreAuthorize} là hàng rào duy nhất, ADR-0149 mục 20).
 * <ul>
 * <li><b>Thứ tự lỗi:</b> 404 → 403 chi nhánh → 403 người phụ trách (#5, #8) / loại việc (#7) → <b>409 trạng thái</b> → 400
 * {@code BR-LT-15} (mọi lỗi rule, kể cả chuỗi rỗng sau {@code strip()}). Khác #4: tóm tắt rỗng của xuất viện đứng trước
 * DB, ở đây rỗng đứng sau 409 (task N5).</li>
 * <li><b>Khóa #5:</b> {@code admissions FOR NO KEY UPDATE} (đọc ca <b>sau</b> khóa) → {@code prescription_items FOR KEY
 * SHARE} (chỉ {@code MEDICATION}) → INSERT {@code admission_orders}. Khóa ca <b>trước</b> INSERT là ràng buộc của ADR-0149
 * mục 23: phép đếm {@code ORDERED} của xuất viện nhờ đó không bị vượt mặt.</li>
 * <li><b>Khóa #7, #8:</b> chỉ dòng y lệnh {@code FOR NO KEY UPDATE}; ca đọc không khóa. Thực hiện / hủy chỉ làm giảm số
 * {@code ORDERED} nên không phá phép đếm của xuất viện (ADR-0149 mục 28).</li>
 * <li>{@code saveAndFlush} là lệnh ghi cuối; response map từ entity sau đó (trong transaction, OSIV tắt).</li>
 * </ul>
 */
@Service
public class AdmissionOrderService {

    private static final String RULE = "BR-LT-15";
    private static final String ROLE_CARETAKER = "CARETAKER";

    private final BranchScope branchScope;
    private final AdmissionRepository admissions;
    private final AdmissionOrderRepository admissionOrders;
    private final AdmissionAssignees assignees;
    private final PrescriptionApi prescriptions;
    private final AdmissionOrderTransitionHandler transitions;
    private final AdmissionOrderMapper mapper;
    private final Clock clock;

    public AdmissionOrderService(BranchScope branchScope, AdmissionRepository admissions,
            AdmissionOrderRepository admissionOrders, AdmissionAssignees assignees, PrescriptionApi prescriptions,
            AdmissionOrderTransitionHandler transitions, AdmissionOrderMapper mapper, Clock clock) {
        this.branchScope = branchScope;
        this.admissions = admissions;
        this.admissionOrders = admissionOrders;
        this.assignees = assignees;
        this.prescriptions = prescriptions;
        this.transitions = transitions;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** inpatient-v1 #5 — Y lệnh nội trú#1 ({@code — → ORDERED}). */
    @Transactional
    public AdmissionOrderResponse create(Long admissionId, CreateAdmissionOrderRequest request) {
        SecurityPrincipal principal = branchScope.current();
        // Khóa rồi mới đọc: findById trước sẽ trả bản managed cũ sau khi chờ khóa (thấy ADMITTED dù ca đã xuất viện).
        Admission admission = admissions.findByIdForUpdate(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        assignees.requireAssignee(admission, principal);
        if (admission.getStatus() != AdmissionStatus.ADMITTED) {
            throw new InvalidStateTransitionException("ca nội trú (chỉ ra y lệnh khi đang điều trị)",
                    admission.getStatus().name(), "ra y lệnh");
        }

        String content = request.content().strip();
        if (content.isEmpty()) {
            throw new BusinessRuleViolationException(RULE, "Vui lòng nhập nội dung y lệnh");
        }
        Long itemId = linkedPrescriptionItem(admission, request);

        Instant scheduledAt = request.scheduledAt().truncatedTo(ChronoUnit.MICROS);
        AdmissionOrder order = AdmissionOrder.order(admission, request.orderType(), content, scheduledAt, itemId,
                principal.accountId());
        admissionOrders.saveAndFlush(order);
        return mapper.toResponse(order);
    }

    /** inpatient-v1 #7 — Y lệnh nội trú#2 ({@code ORDERED → DONE}). */
    @Transactional
    public AdmissionOrderResponse complete(Long admissionOrderId, CompleteAdmissionOrderRequest request) {
        SecurityPrincipal principal = branchScope.current();
        AdmissionOrder order = lockOrder(admissionOrderId);
        branchScope.check(branchOf(order));
        if (isCaretaker(principal) && order.requiresVet()) {
            throw new AccessDeniedScopeException("order:" + order.getOrderType(), "role:CARETAKER");
        }
        transitions.validateTransition(order.getStatus(), AdmissionOrderStatus.DONE);
        if (request.note().strip().isEmpty()) {
            throw new BusinessRuleViolationException(RULE, "Vui lòng nhập ghi chú thực hiện");
        }

        order.complete(principal.accountId(), clock.instant().truncatedTo(ChronoUnit.MICROS), request.note());
        admissionOrders.saveAndFlush(order);
        return mapper.toResponse(order);
    }

    /** inpatient-v1 #8 — Y lệnh nội trú#3 ({@code ORDERED → CANCELLED}). */
    @Transactional
    public AdmissionOrderResponse cancel(Long admissionOrderId, AdmissionOrderReasonRequest request) {
        SecurityPrincipal principal = branchScope.current();
        AdmissionOrder order = lockOrder(admissionOrderId);
        Admission admission = admissionOf(order);
        branchScope.check(admission.getBranchId());
        assignees.requireAssignee(admission, principal);
        transitions.validateTransition(order.getStatus(), AdmissionOrderStatus.CANCELLED);
        if (request.reason().strip().isEmpty()) {
            throw new BusinessRuleViolationException(RULE, "Vui lòng nhập lý do hủy");
        }

        order.cancel(request.reason());
        admissionOrders.saveAndFlush(order);
        return mapper.toResponse(order);
    }

    /**
     * BR-LT-15: {@code MEDICATION} bắt buộc có dòng đơn thuốc của Visit và không phải dòng mua ngoài; mọi loại khác cấm
     * có dòng đơn (Mạnh chốt 24/11, docs/adr/0149 mục 27). Trả id dòng đơn, hoặc null với loại không phải thuốc.
     */
    private Long linkedPrescriptionItem(Admission admission, CreateAdmissionOrderRequest request) {
        Long itemId = request.prescriptionItemId();
        if (request.orderType() != AdmissionOrderType.MEDICATION) {
            if (itemId != null) {
                throw new BusinessRuleViolationException(RULE, "Chỉ y lệnh thuốc mới liên kết dòng đơn thuốc");
            }
            return null;
        }
        if (itemId == null) {
            throw new BusinessRuleViolationException(RULE, "Y lệnh thuốc phải liên kết một dòng đơn thuốc");
        }
        PrescriptionItemRef item = prescriptions.findItemOfVisitForKeyShare(itemId, admission.getVisitId())
                .orElseThrow(() -> new BusinessRuleViolationException(RULE,
                        "Dòng đơn thuốc không thuộc lượt khám của ca nội trú"));
        if (item.externalPurchase()) {
            throw new BusinessRuleViolationException(RULE,
                    "Dòng đơn thuốc mua ngoài không ra y lệnh được; chỉ thuốc của phòng khám");
        }
        return item.itemId();
    }

    /** Khóa dòng y lệnh rồi mới đọc (tx chờ khóa thấy trạng thái đã commit). */
    private AdmissionOrder lockOrder(Long admissionOrderId) {
        return admissionOrders.findByIdForUpdate(admissionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("y lệnh nội trú", admissionOrderId));
    }

    private Long branchOf(AdmissionOrder order) {
        return admissionOf(order).getBranchId();
    }

    /** Ca của y lệnh luôn tồn tại (FK RESTRICT, không xóa cứng): thiếu là dữ liệu hỏng → 500. */
    private Admission admissionOf(AdmissionOrder order) {
        return admissions.findById(order.getAdmissionId())
                .orElseThrow(() -> new IllegalStateException("Thiếu ca nội trú #" + order.getAdmissionId()
                        + " của y lệnh #" + order.getId()));
    }

    private static boolean isCaretaker(SecurityPrincipal principal) {
        return ROLE_CARETAKER.equals(principal.role());
    }
}
