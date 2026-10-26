package com.petcare.module.visit.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.dto.AddPrescriptionRequest;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.dto.ShortageResolution;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.PrescriptionItem;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository.LockedItem;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Kê đơn — visit-v1 #16 (UC48; BR-KB-03, BR-SP-01, BR-BH-02, BR-QT-15; docs/adr/0077) và xóa dòng đơn #17 (docs/adr/0078).
 * Mỗi use case một transaction ghi, READ COMMITTED. Phần còn lại của javadoc này mô tả #16; #17 ở {@link #remove}.
 * <p>
 * Thứ tự: role → lượt (404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → {@code pets FOR SHARE} → {@code visits} →
 * người phụ trách (403) → {@code IN_PROGRESS} (409) → thuốc {@code FOR SHARE} (400 BR-KB-03) → tồn khả dụng (400
 * BR-KB-03) → bệnh án rỗng nếu chưa có → {@code orders} (dòng DRUG, nếu có) → INSERT dòng đơn thuốc → audit. Mọi guard
 * chạy trước lệnh ghi đầu tiên. Khóa thú vì tạo bệnh án làm thú bị khóa đổi loài (BR-KH-03); thuốc đọc {@code FOR SHARE}
 * <b>trước</b> {@code addDrugLine} để thuốc ngừng kinh doanh chen giữa không thành 500 ở sales.
 * <p>
 * Tồn khả dụng đọc không khóa và không trừ thuốc đã kê chưa thu (BR-KO-01, A28): kho chỉ trừ lúc thu, nơi BR-BH-04 kiểm
 * lại. Không {@code catch}: lỗi từ callee {@code MANDATORY} đã đánh dấu rollback-only.
 */
@Service
public class PrescriptionService {

    /** Hợp đồng #16: A07, VET được gán lượt. */
    static final Set<String> ROLES = Set.of("VET");

    private final BranchScope branchScope;
    private final VisitRepository visits;
    private final MedicalRecordRepository records;
    private final PrescriptionItemRepository items;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final StockQueryApi stockQuery;
    private final VisitOrderApi visitOrders;
    private final AuditRecorder audit;
    private final MedicalRecordMapper mapper;

    public PrescriptionService(BranchScope branchScope, VisitRepository visits, MedicalRecordRepository records,
            PrescriptionItemRepository items, PetQueryApi pets, CatalogQueryApi catalog, StockQueryApi stockQuery,
            VisitOrderApi visitOrders, AuditRecorder audit, MedicalRecordMapper mapper) {
        this.branchScope = branchScope;
        this.visits = visits;
        this.records = records;
        this.items = items;
        this.pets = pets;
        this.catalog = catalog;
        this.stockQuery = stockQuery;
        this.visitOrders = visitOrders;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public PrescriptionItemResponse add(Long visitId, AddPrescriptionRequest request) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, ROLES);
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());
        if (summary.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("lượt khám / tiêm", visitId);
        }

        pets.findPetForShare(summary.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + summary.petId() + " của lượt biến mất"));
        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);

        ProductInfo product = requirePrescriptionDrug(request.productId());
        int available = stockQuery.availableQuantity(summary.branchId(), product.productId());
        Integer quantity = decideQuantity(request.quantity(), available, request.shortageResolution());

        // Từ đây mới ghi: mọi guard ở trên không để lại gì.
        if (records.findById(visitId).isEmpty()) {
            records.saveAndFlush(MedicalRecord.of(visitId));
        }
        PrescriptionItem item;
        if (quantity == null) {
            item = PrescriptionItem.externalAtPrescribe(visitId, product.productId(), request.quantity(),
                    request.dosageInstructions());
        } else {
            LineRef line = visitOrders.addDrugLine(visitId, product.productId(), quantity, principal.accountId());
            item = PrescriptionItem.prescribe(visitId, product.productId(), quantity, request.dosageInstructions(),
                    line.orderLineId());
        }
        PrescriptionItem saved = items.saveAndFlush(item);
        audit.record(AuditEntry.of(VisitAuditActions.PRESCRIPTION_ITEM_ADDED).entity("prescription_items", saved.getId())
                .after(snapshot(visitId, saved, request.quantity())));
        return mapper.toPrescriptionItem(saved, product.name());
    }

    /**
     * Xóa một dòng đơn thuốc — visit-v1 #17 (UC48; BR-KB-03, BR-BH-02, BR-QT-15; docs/adr/0078). Một transaction ghi,
     * READ COMMITTED (đọc người phụ trách / trạng thái sau khóa phải thấy dữ liệu mới nhất).
     * <p>
     * Thứ tự: role → lượt (404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → {@code visits} → người phụ trách (403) →
     * {@code IN_PROGRESS} (409) → dòng đơn của lượt {@code FOR UPDATE} (404) → DELETE dòng đơn → xóa dòng Order {@code DRUG}
     * (nếu không phải mua ngoài) → audit. Dòng đơn xóa <b>trước</b> dòng Order vì FK {@code order_line_id} là
     * {@code RESTRICT}. Không khóa thú (không tạo bệnh án); bệnh án rỗng do #16 tạo được giữ nguyên. Không {@code catch}.
     */
    @Transactional
    public void remove(Long visitId, Long itemId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, ROLES);
        SummaryRow summary = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(summary.branchId());
        if (summary.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("lượt khám / tiêm", visitId);
        }

        Visit visit = lockAndLoad(visitId);
        requireAssigneeInProgress(visit, principal);
        LockedItem item = items.lockItemInVisit(itemId, visitId)
                .orElseThrow(() -> new ResourceNotFoundException("dòng đơn thuốc", itemId));

        if (items.deleteItem(item.getId()) != 1) {
            throw new IllegalStateException("Dòng đơn thuốc #" + item.getId() + " đã khóa nhưng không xóa được");
        }
        if (item.getOrderLineId() != null) {
            visitOrders.removeDrugLine(item.getOrderLineId(), principal.accountId());
        }
        audit.record(AuditEntry.of(VisitAuditActions.PRESCRIPTION_ITEM_REMOVED).entity("prescription_items", item.getId())
                .before(snapshot(visitId, item)));
    }

    /**
     * Bảng quyết định BR-KB-03 (docs/adr/0077 §3): số lượng của dòng {@code DRUG}, hoặc {@code null} = mua ngoài. Đủ tồn
     * thì bỏ qua {@code shortageResolution} (A26); thiếu mà chưa chọn, hoặc chọn giảm khi tồn bằng 0 (A27), là 400.
     */
    static Integer decideQuantity(int requested, int available, ShortageResolution resolution) {
        if (available >= requested) {
            return requested;
        }
        if (resolution == null) {
            throw new BusinessRuleViolationException("BR-KB-03", "Tồn khả dụng tại chi nhánh chỉ còn " + available
                    + "; chọn giảm số lượng hoặc mua ngoài");
        }
        return switch (resolution) {
            case EXTERNAL_PURCHASE -> null;
            case REDUCE_TO_AVAILABLE -> {
                if (available < 1) {
                    throw new BusinessRuleViolationException("BR-KB-03",
                            "Thuốc đã hết tồn khả dụng tại chi nhánh, chỉ chọn được mua ngoài");
                }
                yield available;
            }
        };
    }

    /**
     * Thuốc kê đơn đang kinh doanh (BR-KB-03, BR-SP-01), kể cả khi chọn mua ngoài. Khóa chia sẻ (không bao giờ
     * {@code findProduct}): ngừng kinh doanh song song phải chờ, không thành 500 ở {@code addDrugLine}.
     */
    private ProductInfo requirePrescriptionDrug(Long productId) {
        return catalog.findProductForShare(productId)
                .filter(p -> p.active() && p.type() == ProductType.DRUG && p.prescription())
                .orElseThrow(() -> new BusinessRuleViolationException("BR-KB-03",
                        "Sản phẩm không phải thuốc kê đơn đang kinh doanh"));
    }

    /** {@code LinkedHashMap} giữ thứ tự khóa trong JSON audit; {@code requestedQuantity} là số VET gửi (docs/adr/0077 S4). */
    private static Map<String, Object> snapshot(Long visitId, PrescriptionItem item, int requestedQuantity) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("visitId", visitId);
        after.put("productId", item.getProductId());
        after.put("quantity", item.getQuantity());
        after.put("requestedQuantity", requestedQuantity);
        after.put("dosageInstructions", item.getDosageInstructions());
        after.put("isExternalPurchase", item.isExternalPurchase());
        after.put("externalReason", item.getExternalReason() == null ? null : item.getExternalReason().name());
        after.put("orderLineId", item.getOrderLineId());
        return after;
    }

    /** {@code before} của #17: cùng khóa và thứ tự với {@code after} của #16 trừ {@code requestedQuantity} (docs/adr/0078). */
    private static Map<String, Object> snapshot(Long visitId, LockedItem item) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("visitId", visitId);
        before.put("productId", item.getProductId());
        before.put("quantity", item.getQuantity());
        before.put("dosageInstructions", item.getDosageInstructions());
        before.put("isExternalPurchase", item.getExternalPurchase());
        before.put("externalReason", item.getExternalReason());
        before.put("orderLineId", item.getOrderLineId());
        return before;
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
