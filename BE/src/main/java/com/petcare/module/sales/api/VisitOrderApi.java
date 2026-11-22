package com.petcare.module.sales.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Owner: sales (BH) · BE-2. Caller: visit (TN, KB) · BE-1.
 * Order nguồn VISIT, mỗi Visit đúng 1 Order (BR-BH-01). Gọi trong transaction của Visit.
 */
public interface VisitOrderApi {

    record LineRef(Long orderId, Long orderLineId) {}

    /**
     * Visit#1 → Order#1: tạo Order OPEN kèm 1 dòng dịch vụ tự sinh, đơn giá snapshot (BR-TN-01, BR-BH-03).
     * {@code ownerAccountId} = người được gán lượt; null nếu chưa gán, khi đó owner tạm là {@code actorId}.
     * Dịch vụ đã ngừng kinh doanh vẫn mở được Order (lịch hẹn đã đặt trước khi ngừng, docs/adr/0088); dịch vụ không tồn
     * tại thì {@code IllegalStateException}. Bên gọi tự kiểm "đang kinh doanh" khi khách walk-in.
     */
    LineRef openForVisit(Long visitId, Long branchId, Long customerId, Long serviceId,
                         Long ownerAccountId, Long actorId);

    /** Visit#2: dòng tự sinh thuộc quyền nhân viên được gán (erd `order_lines.owner_account_id`). */
    void setAutoLineOwner(Long visitId, Long assigneeId);

    /** Visit#4: quyền xóa các dòng của người cũ chuyển cho người mới (BR-TN-08). */
    void transferLineOwnership(Long visitId, Long fromAccountId, Long toAccountId);

    /** Visit#5 → Order#4. */
    void markPendingByVisit(Long visitId);

    /** Visit#6 → Order#6 ({@code cancel_type = VISIT_CANCELLED}). */
    void cancelByVisit(Long visitId, Long actorId);

    /** Dịch vụ đang có trong Order của Visit — điều kiện hoàn tất lượt theo loại EXAM/VACCINE (BR-KB-02). */
    List<Long> findServiceIds(Long visitId);

    /**
     * Như {@link #findServiceIds} cho nhiều lượt một lần (AI-0, cho {@code VisitQueryApi} I-6): {@code visitId → các
     * serviceId} (theo thứ tự dòng, có thể lặp nếu Order có hai dòng cùng dịch vụ); lượt không có Order hoặc không có
     * dòng dịch vụ thì vắng mặt, <b>không ném</b>. Đọc không khóa, tham gia transaction của bên gọi; tập rỗng → map rỗng.
     */
    Map<Long, List<Long>> findServiceIdsByVisitIds(Collection<Long> visitIds);

    /** BR-KB-03 đủ tồn: dòng đơn thuốc sinh 1 dòng DRUG, owner = VET. */
    LineRef addDrugLine(Long visitId, Long productId, int quantity, Long actorId);

    /** VET xóa dòng đơn thuốc khi Order còn OPEN. */
    void removeDrugLine(Long orderLineId, Long actorId);

    /** BR-KB-04: mỗi mũi tiêm sinh 1 dòng VACCINE, số lượng 1. */
    LineRef addVaccineLine(Long visitId, Long productId, Long actorId);

    /** BR-KB-04: chỉ visit gọi khi xóa mũi tiêm ghi nhầm; dòng vaccine không xóa trực tiếp (BR-BH-02). */
    void removeVaccineLine(Long orderLineId);

    // ------------------------------------------------------------------------------------------------------------
    // Nội trú (docs/09 §C, ADR-0124, ADR-0148; T69): dạng default để không làm gãy các stand-in {@code new VisitOrderApi() {…}}
    // trong test của BE-2; VisitOrderService ghi đè cả hai, nên default chỉ là lưới an toàn.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Order#14 (— → {@code OPEN}, nguồn VISIT, SYS ← Visit#7): Order của Visit điều trị (nhập viện từ lưu trú, BR-LT-13b),
     * <b>không có dòng dịch vụ tự sinh</b> (ngoại lệ của BR-TN-01: dòng nội trú chỉ có lúc xuất viện, BR-LT-14).
     *
     * @return {@code orders.id}
     */
    default Long openTreatmentOrder(Long visitId, Long branchId, Long customerId, Long actorId) {
        throw new UnsupportedOperationException("VisitOrderApi.openTreatmentOrder chưa cài (docs/09 §C, T69)");
    }

    /**
     * BR-LT-14: xuất viện thêm <b>một</b> dòng dịch vụ nội trú vào Order {@code OPEN} của Visit. Nhận <b>đơn giá đã
     * chốt</b> lúc nhập viện ({@code admissions.unit_price}, BR-BH-03), không tra giá hiện hành; không kiểm "đang kinh
     * doanh" hay bật tại chi nhánh (dịch vụ có thể đã ngừng sau khi nhập) và không áp {@code OrderLinePolicy} vì bên gọi
     * (inpatient) đã tự phân quyền VET phụ trách. Owner của dòng = {@code actorId}. Khóa Order trước khi đọc (D045).
     *
     * @throws com.petcare.platform.exception.InvalidStateTransitionException Order không {@code OPEN}
     */
    default LineRef addServiceLine(Long visitId, Long serviceId, int quantity, long unitPrice, Long actorId) {
        throw new UnsupportedOperationException("VisitOrderApi.addServiceLine chưa cài (docs/09 §C, T69)");
    }
}
