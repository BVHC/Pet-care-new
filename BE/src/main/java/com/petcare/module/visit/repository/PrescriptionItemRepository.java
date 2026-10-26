package com.petcare.module.visit.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.entity.PrescriptionItem;

/**
 * Bảng {@code prescription_items} (docs/adr/0076). {@code medical_record_id} chưa có index (nợ D043): đọc theo bệnh án
 * quét bảng, chấp nhận ở giai đoạn 1 vì mỗi lượt khám chỉ vài dòng.
 */
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {

    /** Đơn thuốc của một bệnh án theo thứ tự kê (visit-v1 #12, #13); gồm cả dòng mua ngoài (vẫn in trên đơn). */
    List<PrescriptionItem> findByMedicalRecordIdOrderByIdAsc(Long medicalRecordId);

    /**
     * BR-BH-04: thiếu tồn lúc thu, dòng đơn thuốc đang gắn với {@code orderLineId} chuyển sang mua ngoài. Một câu UPDATE
     * đặt cả ba cột cùng lúc (CHECK {@code is_external_purchase = (order_line_id IS NULL)} không cho tách dòng), tự khóa
     * dòng qua unique index của {@code order_line_id}; không đổi thuốc, số lượng, liều. Dòng đã mua ngoài có
     * {@code order_line_id} NULL nên không khớp điều kiện. {@code updated_at} đặt từ {@code Clock} (docs/adr/0015).
     * Trả số dòng đổi: 1, hoặc 0 khi {@code orderLineId} không phải dòng đơn thuốc nào.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE prescription_items
            SET is_external_purchase = true, external_reason = 'OUT_OF_STOCK_AT_PAYMENT', order_line_id = NULL,
                updated_at = :now
            WHERE order_line_id = :orderLineId
            """, nativeQuery = true)
    int markExternalAtPayment(@Param("orderLineId") Long orderLineId, @Param("now") Instant now);

    /**
     * Khóa dòng đơn thuốc của lượt để xóa (visit-v1 #17, docs/adr/0078): {@code FOR UPDATE} vì dòng sắp bị DELETE. Lọc cả
     * {@code medical_record_id}: dòng của lượt khác là "không có" (404, không lộ sự tồn tại). Chỉ projection, không nạp
     * entity (bulk DELETE sau đó không đồng bộ persistence context). Bên gọi đã khóa {@code visits}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id AS id, product_id AS productId, quantity AS quantity, dosage_instructions AS dosageInstructions,
                   is_external_purchase AS externalPurchase, external_reason AS externalReason,
                   order_line_id AS orderLineId
            FROM prescription_items
            WHERE id = :id AND medical_record_id = :visitId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedItem> lockItemInVisit(@Param("id") Long id, @Param("visitId") Long visitId);

    /**
     * Xóa cứng một dòng đơn thuốc (BR-KB-03, erd §0 / §13 mục 21). Phải xóa trước dòng Order: {@code order_line_id} là FK
     * {@code RESTRICT}. Trả số dòng xóa (bên gọi đã khóa dòng nên phải là 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "DELETE FROM prescription_items WHERE id = :id", nativeQuery = true)
    int deleteItem(@Param("id") Long id);

    /** Projection của {@link #lockItemInVisit}: {@code orderLineId} đi thẳng vào {@code removeDrugLine}, null = mua ngoài. */
    interface LockedItem {

        Long getId();

        Long getProductId();

        Integer getQuantity();

        String getDosageInstructions();

        Boolean getExternalPurchase();

        String getExternalReason();

        Long getOrderLineId();
    }
}
