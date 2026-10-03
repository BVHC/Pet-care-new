package com.petcare.module.sales.api;

import java.util.List;

/**
 * Owner: sales (BH) · BE-2. Caller: visit (TN, KB) · BE-1.
 * Order nguồn VISIT, mỗi Visit đúng 1 Order (BR-BH-01). Gọi trong transaction của Visit.
 */
public interface VisitOrderApi {

    record LineRef(Long orderId, Long orderLineId) {}

    /**
     * Visit#1 → Order#1: tạo Order OPEN kèm 1 dòng dịch vụ tự sinh, đơn giá snapshot (BR-TN-01, BR-BH-03).
     * {@code ownerAccountId} = người được gán lượt; null nếu chưa gán, khi đó owner tạm là {@code actorId}.
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

    /** BR-KB-03 đủ tồn: dòng đơn thuốc sinh 1 dòng DRUG, owner = VET. */
    LineRef addDrugLine(Long visitId, Long productId, int quantity, Long actorId);

    /** VET xóa dòng đơn thuốc khi Order còn OPEN. */
    void removeDrugLine(Long orderLineId, Long actorId);

    /** BR-KB-04: mỗi mũi tiêm sinh 1 dòng VACCINE, số lượng 1. */
    LineRef addVaccineLine(Long visitId, Long productId, Long actorId);

    /** BR-KB-04: chỉ visit gọi khi xóa mũi tiêm ghi nhầm; dòng vaccine không xóa trực tiếp (BR-BH-02). */
    void removeVaccineLine(Long orderLineId);
}
