package com.petcare.module.visit.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code vaccinations} (erd §6, ROOT): một mũi tiêm thực hiện tại hệ thống (BR-KB-04). Mọi cột là giá trị đã chụp
 * lúc tiêm ({@code dose_number}, {@code next_due_date}, {@code branch_id}): sửa phác đồ không đổi lịch sử (BR-SP-03).
 * Chỉ có factory {@link #record} (T28 phần 2, docs/adr/0074), không setter; {@code superseded_at} (BR-TB-03) và
 * {@code due_reminded_at} (ST04) được đặt bằng bulk UPDATE của repository; hai cột đó là cột duy nhất {@code updatable}.
 * {@code overdue_handled_at} (ST18, docs/adr/0083) cũng chỉ ghi bằng bulk UPDATE nên entity chỉ đọc.
 */
@Getter
@Entity
@Table(name = "vaccinations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vaccination extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "vaccine_type_id", nullable = false, updatable = false)
    private Long vaccineTypeId;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private Long visitId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "administered_on", nullable = false, updatable = false)
    private LocalDate administeredOn;

    @Column(name = "protocol_id", nullable = false, updatable = false)
    private Long protocolId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "dose_number", nullable = false, updatable = false)
    private int doseNumber;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "stock_lot_id", nullable = false, updatable = false)
    private Long stockLotId;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private Long orderLineId;

    @Column(name = "next_due_date", nullable = false, updatable = false)
    private LocalDate nextDueDate;

    @Column(name = "due_reminded_at")
    private Instant dueRemindedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** ST18 đã xét quá hạn (BR-TB-04, docs/adr/0083). Chỉ đọc: cột chỉ được ghi bằng bulk UPDATE của repository. */
    @Column(name = "overdue_handled_at", insertable = false, updatable = false)
    private Instant overdueHandledAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    /**
     * Mũi tiêm mới ghi nhận (visit-v1 #19, docs/adr/0074). Mọi giá trị là giá trị chụp lúc tiêm; {@code superseded_at}
     * và {@code due_reminded_at} để trống.
     */
    public static Vaccination record(Long petId, Long vaccineTypeId, Long visitId, Long branchId, LocalDate administeredOn,
            Long protocolId, int doseNumber, Long productId, Long stockLotId, Long orderLineId, LocalDate nextDueDate,
            Long recordedBy) {
        Vaccination v = new Vaccination();
        v.petId = petId;
        v.vaccineTypeId = vaccineTypeId;
        v.visitId = visitId;
        v.branchId = branchId;
        v.administeredOn = administeredOn;
        v.protocolId = protocolId;
        v.doseNumber = doseNumber;
        v.productId = productId;
        v.stockLotId = stockLotId;
        v.orderLineId = orderLineId;
        v.nextDueDate = nextDueDate;
        v.recordedBy = recordedBy;
        return v;
    }

    /** Đã có mũi mới hơn cùng loại vaccine (BR-TB-03). */
    public boolean isSuperseded() {
        return supersededAt != null;
    }
}
