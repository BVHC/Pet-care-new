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
 * T28 phần 0 chỉ đọc (docs/adr/0072): chưa có factory và chưa có setter; chỉ {@code due_reminded_at} (ST04) và
 * {@code superseded_at} (BR-TB-03) sẽ có bên ghi ở các phần sau, nên hai cột đó là cột duy nhất {@code updatable}.
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

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    /** Đã có mũi mới hơn cùng loại vaccine (BR-TB-03). */
    public boolean isSuperseded() {
        return supersededAt != null;
    }
}
