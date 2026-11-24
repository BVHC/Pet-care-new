package com.petcare.module.inpatient.entity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.petcare.platform.config.TimeConfig;
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
 * Bảng {@code admissions} (V38, ROOT, 09 §C FSM #24): một ca nội trú của một thú gắn với một Visit (BR-LT-13). Cột nhập
 * viện là {@code updatable = false}; chỉ nhóm {@code status} và các cột xuất viện đổi khi xuất viện (BR-LT-14).
 * <p>
 * Entity giữ các bất biến tự thân (docs/adr/0149 §1b): đúng một nguồn (chuồng điều trị <b>hoặc</b> đặt chỗ lưu trú, khớp
 * {@code ck_admissions_source} V62), giá đã chốt không âm, xuất viện chỉ từ {@code ADMITTED}, không xuất viện khi còn
 * y lệnh {@code ORDERED}, số ngày tính tiền và tiền tạm tính. Điều kiện cần dữ liệu module khác (Visit, đặt chỗ, chuồng,
 * dịch vụ {@code INPATIENT}, thú chưa mất) do service kiểm trước; các method ở đây là lớp chặn cuối, không thay
 * {@code AdmissionTransitionHandler}.
 */
@Getter
@Entity
@Table(name = "admissions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Admission extends TimestampedEntity {

    /** Khóa dãy của {@code DocumentCodeGenerator} (docs/adr/0148 mục 7). */
    public static final String CODE_DOC_TYPE = "admissions";
    /** Tiền tố mã ca {@code NT-yyMM-xxxxx} (docs/adr/0148 mục 7). */
    public static final String CODE_PREFIX = "NT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false, length = InpatientText.CODE_MAX_LENGTH)
    private String code;

    @Column(name = "visit_id", nullable = false, updatable = false)
    private Long visitId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** Có khi nhập từ lưu trú (đường b); thú ở nguyên chuồng của đặt chỗ. */
    @Column(name = "boarding_booking_id", updatable = false)
    private Long boardingBookingId;

    /** Có khi nhập từ lượt khám (đường a): chuồng điều trị cấp riêng. */
    @Column(name = "kennel_id", updatable = false)
    private Long kennelId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private Long serviceId;

    /** Đơn giá theo ngày, chốt lúc nhập viện (BR-BH-03). */
    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AdmissionStatus status;

    @Column(name = "admitted_by", nullable = false, updatable = false)
    private Long admittedBy;

    @Column(name = "admitted_at", nullable = false, updatable = false)
    private Instant admittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "discharge_outcome", length = 30)
    private DischargeOutcome dischargeOutcome;

    @Column(name = "discharge_summary")
    private String dischargeSummary;

    @Column(name = "discharged_by")
    private Long dischargedBy;

    @Column(name = "discharged_at")
    private Instant dischargedAt;

    /** Dòng dịch vụ nội trú đã thêm vào Order lúc xuất viện (BR-LT-14). */
    @Column(name = "order_line_id")
    private Long orderLineId;

    /**
     * Nhập viện#1: {@code — → ADMITTED}. Đúng một trong {@code boardingBookingId} (nhập từ lưu trú) và {@code kennelId}
     * (nhập từ lượt khám) (BR-LT-13, {@code ck_admissions_source}). {@code code} do bên gọi sinh bằng
     * {@code DocumentCodeGenerator.next(CODE_DOC_TYPE, CODE_PREFIX, ngày)} <b>sau cùng</b> trong transaction.
     */
    public static Admission admit(String code, Long visitId, Long petId, Long branchId, Long boardingBookingId,
            Long kennelId, Long serviceId, long unitPrice, Long admittedBy, Instant admittedAt) {
        if ((boardingBookingId == null) == (kennelId == null)) {
            throw new IllegalArgumentException("Ca nội trú cần đúng một nguồn: đặt chỗ lưu trú hoặc chuồng điều trị");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("Đơn giá nội trú không được âm");
        }
        Admission entity = new Admission();
        entity.code = InpatientText.require(code, InpatientText.CODE_MAX_LENGTH, "code");
        entity.visitId = requireNonNull(visitId, "visitId");
        entity.petId = requireNonNull(petId, "petId");
        entity.branchId = requireNonNull(branchId, "branchId");
        entity.boardingBookingId = boardingBookingId;
        entity.kennelId = kennelId;
        entity.serviceId = requireNonNull(serviceId, "serviceId");
        entity.unitPrice = unitPrice;
        entity.admittedBy = requireNonNull(admittedBy, "admittedBy");
        entity.admittedAt = requireNonNull(admittedAt, "admittedAt");
        entity.status = AdmissionStatus.ADMITTED;
        return entity;
    }

    /**
     * Nhập viện#2: {@code ADMITTED → DISCHARGED} (BR-LT-14). {@code orderedCount} là số y lệnh {@code ORDERED} của ca
     * do service đếm dưới khóa; còn y lệnh thì không xuất viện. {@code orderLineId} là dòng dịch vụ nội trú vừa thêm
     * vào Order ({@code ck_admissions_discharge_fields}). Tóm tắt điều trị bắt buộc, không rỗng sau {@code strip()}.
     */
    public void discharge(DischargeOutcome outcome, String summary, Long by, Instant at, Long orderLineId,
            long orderedCount) {
        requireAdmitted();
        if (orderedCount < 0) {
            throw new IllegalArgumentException("orderedCount không được âm");
        }
        if (orderedCount > 0) {
            throw new IllegalStateException("Còn " + orderedCount + " y lệnh chưa thực hiện hoặc hủy");
        }
        DischargeOutcome result = requireNonNull(outcome, "outcome");
        String text = requireNonNull(summary, "summary").strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Tóm tắt điều trị không được để trống");
        }
        Long actor = requireNonNull(by, "by");
        Instant when = requireNonNull(at, "at");
        Long line = requireNonNull(orderLineId, "orderLineId");
        if (when.isBefore(admittedAt)) {
            throw new IllegalArgumentException("Lúc xuất viện không được trước lúc nhập viện");
        }
        this.status = AdmissionStatus.DISCHARGED;
        this.dischargeOutcome = result;
        this.dischargeSummary = text;
        this.dischargedBy = actor;
        this.dischargedAt = when;
        this.orderLineId = line;
    }

    /** Lớp chặn cuối cho mọi thao tác chỉ làm được khi ca còn đang nội trú (xuất viện, ra y lệnh, ghi theo dõi). */
    public void requireAdmitted() {
        if (status != AdmissionStatus.ADMITTED) {
            throw new IllegalStateException("Ca nội trú không còn ở trạng thái ADMITTED: " + status);
        }
    }

    /**
     * Số ngày tính tiền (BR-LT-14): ngày lịch giờ Việt Nam của mốc cuối trừ ngày nhập, cộng 1 (gồm cả hai đầu), tối
     * thiểu 1. Mốc cuối là {@code dischargedAt} khi đã xuất viện (con số đứng yên), ngược lại là {@code now}. Dùng
     * chung cho số lượng dòng Order lúc xuất viện và cho {@code billedDays} của API đọc.
     */
    public int billedDays(Instant now) {
        Instant end = status == AdmissionStatus.DISCHARGED ? dischargedAt : requireNonNull(now, "now");
        LocalDate from = admittedAt.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        LocalDate to = end.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return (int) Math.max(1, Math.min(days, Integer.MAX_VALUE));
    }

    /** Tiền tạm tính {@code billedDays × unitPrice} (không lưu); {@link ArithmeticException} nếu tràn {@code long}. */
    public long estimatedAmount(Instant now) {
        return Math.multiplyExact((long) billedDays(now), unitPrice);
    }
}
