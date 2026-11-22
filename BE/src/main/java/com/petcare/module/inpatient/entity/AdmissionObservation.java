package com.petcare.module.inpatient.entity;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.petcare.platform.model.CreatedAtEntity;

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
 * Bảng {@code admission_observations} (V38, LOG, BR-LT-16): chỉ số theo dõi nội trú, <b>chỉ thêm, không sửa, không
 * xóa</b> nên mọi cột {@code updatable = false} và không có method đổi. Giới hạn chỉ số chỉ là giới hạn lưu trữ của
 * cột V38 và BR-KH-04 (cân nặng &gt; 0); khoảng lâm sàng chưa được chốt (TBD, docs/adr/0149 Q8).
 */
@Getter
@Entity
@Table(name = "admission_observations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionObservation extends CreatedAtEntity {

    /** {@code temperature_c NUMERIC(4,1)}: giá trị tuyệt đối dưới 1000 (3 chữ số nguyên, 1 chữ số lẻ). */
    private static final BigDecimal TEMPERATURE_LIMIT = new BigDecimal("1000");
    /** {@code weight_kg NUMERIC(6,2)}: giá trị dưới 10000 (4 chữ số nguyên, 2 chữ số lẻ). */
    private static final BigDecimal WEIGHT_LIMIT = new BigDecimal("10000");
    /** {@code heart_rate}, {@code respiratory_rate} là {@code SMALLINT}. */
    private static final int SMALLINT_MAX = Short.MAX_VALUE;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admission_id", nullable = false, updatable = false)
    private Long admissionId;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    @Column(name = "temperature_c", updatable = false, precision = 4, scale = 1)
    private BigDecimal temperatureC;

    @Column(name = "heart_rate", updatable = false)
    private Short heartRate;

    @Column(name = "respiratory_rate", updatable = false)
    private Short respiratoryRate;

    @Column(name = "weight_kg", updatable = false, precision = 6, scale = 2)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "appetite", updatable = false, length = 30)
    private Appetite appetite;

    @Column(name = "note", updatable = false, length = InpatientText.CONTENT_MAX_LENGTH)
    private String note;

    @Column(name = "abnormal", nullable = false, updatable = false)
    private boolean abnormal;

    /**
     * Ghi một mục theo dõi. Chỉ ghi được cho ca còn {@code ADMITTED} (lớp chặn cuối cho 409 của inpatient-v1 #9) và
     * phải có ít nhất một chỉ số hoặc ghi chú. Nhiệt độ làm tròn về một chữ số lẻ, cân nặng về hai (khớp cột).
     * Ghi cân nặng vào hồ sơ cân (BR-KH-04) và kéo thông báo khi {@code abnormal} là việc của service.
     */
    public static AdmissionObservation record(Admission admission, Long recordedBy, BigDecimal temperatureC,
            Integer heartRate, Integer respiratoryRate, BigDecimal weightKg, Appetite appetite, String note,
            boolean abnormal) {
        requireNonNull(admission, "admission").requireAdmitted();
        String text = InpatientText.optional(note, InpatientText.CONTENT_MAX_LENGTH, "note");
        if (temperatureC == null && heartRate == null && respiratoryRate == null && weightKg == null
                && appetite == null && text == null) {
            throw new IllegalArgumentException("Mục theo dõi cần ít nhất một chỉ số hoặc ghi chú");
        }
        BigDecimal weight = weightKg == null ? null : weightKg.setScale(2, RoundingMode.HALF_UP);
        if (weight != null && weight.signum() <= 0) {
            // kiểm sau khi làm tròn: 0.001 thành 0.00 thì không còn dương (BR-KH-04)
            throw new IllegalArgumentException("Cân nặng phải lớn hơn 0");
        }
        AdmissionObservation entity = new AdmissionObservation();
        entity.admissionId = requireNonNull(admission.getId(), "admission.id");
        entity.recordedBy = requireNonNull(recordedBy, "recordedBy");
        entity.temperatureC = temperatureC == null ? null
                : fit(temperatureC.setScale(1, RoundingMode.HALF_UP), TEMPERATURE_LIMIT, "temperatureC");
        entity.heartRate = positiveShort(heartRate, "heartRate");
        entity.respiratoryRate = positiveShort(respiratoryRate, "respiratoryRate");
        entity.weightKg = weight == null ? null : fit(weight, WEIGHT_LIMIT, "weightKg");
        entity.appetite = appetite;
        entity.note = text;
        entity.abnormal = abnormal;
        return entity;
    }

    private static BigDecimal fit(BigDecimal value, BigDecimal limit, String field) {
        if (value.abs().compareTo(limit) >= 0) {
            throw new IllegalArgumentException(field + " vượt giới hạn lưu trữ");
        }
        return value;
    }

    private static Short positiveShort(Integer value, String field) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > SMALLINT_MAX) {
            throw new IllegalArgumentException(field + " phải từ 1 đến " + SMALLINT_MAX);
        }
        return value.shortValue();
    }
}
