package com.petcare.module.visit.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code prescription_items} (erd §6, PART của {@code MedicalRecord}): một dòng đơn thuốc (BR-KB-03). Hoặc sinh đúng
 * 1 dòng Order ({@code order_line_id}), hoặc là <b>mua ngoài</b> (không có dòng Order, vẫn in trên đơn) — CHECK của V1 giữ
 * "không tách một dòng thành hai phần". Nội dung dòng (thuốc, số lượng, liều) là cột {@code updatable = false}: contract
 * không có thao tác sửa dòng. Ba cột còn lại ({@code is_external_purchase}, {@code order_line_id}, {@code external_reason})
 * chỉ đổi bằng bulk UPDATE của repository lúc thu tiền thiếu tồn (BR-BH-04, docs/adr/0076). Dòng mới chỉ tạo qua hai
 * factory của kê đơn (visit-v1 #16, docs/adr/0077), mỗi factory đặt cả ba cột cùng lúc nên luôn giữ "mua ngoài ⇔ có lý
 * do" (DB chưa có CHECK này — nợ D043).
 */
@Getter
@Entity
@Table(name = "prescription_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrescriptionItem extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medical_record_id", nullable = false, updatable = false)
    private Long medicalRecordId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "dosage_instructions", nullable = false, updatable = false, length = 500)
    private String dosageInstructions;

    @Column(name = "is_external_purchase", nullable = false, updatable = false)
    private boolean externalPurchase;

    @Column(name = "order_line_id", updatable = false)
    private Long orderLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_reason", length = 30, updatable = false)
    private ExternalPurchaseReason externalReason;

    /** Độ dài tối đa của liều dùng ({@code dosage_instructions VARCHAR(500)}, erd §6). */
    static final int DOSAGE_MAX_LENGTH = 500;

    /** BR-KB-03 đủ tồn: dòng đơn thuốc gắn đúng 1 dòng Order {@code DRUG} vừa sinh. */
    public static PrescriptionItem prescribe(Long medicalRecordId, Long productId, int quantity,
            String dosageInstructions, Long orderLineId) {
        if (orderLineId == null) {
            throw new IllegalArgumentException("orderLineId is required");
        }
        PrescriptionItem item = of(medicalRecordId, productId, quantity, dosageInstructions);
        item.externalPurchase = false;
        item.orderLineId = orderLineId;
        item.externalReason = null;
        return item;
    }

    /** BR-KB-03 thiếu tồn, VET chọn mua ngoài lúc kê: giữ nguyên số lượng, không có dòng Order, vẫn in trên đơn. */
    public static PrescriptionItem externalAtPrescribe(Long medicalRecordId, Long productId, int quantity,
            String dosageInstructions) {
        PrescriptionItem item = of(medicalRecordId, productId, quantity, dosageInstructions);
        item.externalPurchase = true;
        item.orderLineId = null;
        item.externalReason = ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE;
        return item;
    }

    private static PrescriptionItem of(Long medicalRecordId, Long productId, int quantity, String dosageInstructions) {
        if (medicalRecordId == null || productId == null) {
            throw new IllegalArgumentException("medicalRecordId and productId are required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (dosageInstructions == null || dosageInstructions.isBlank()
                || dosageInstructions.length() > DOSAGE_MAX_LENGTH) {
            throw new IllegalArgumentException("dosageInstructions must be 1.." + DOSAGE_MAX_LENGTH + " characters");
        }
        PrescriptionItem item = new PrescriptionItem();
        item.medicalRecordId = medicalRecordId;
        item.productId = productId;
        item.quantity = quantity;
        item.dosageInstructions = dosageInstructions;
        return item;
    }
}
