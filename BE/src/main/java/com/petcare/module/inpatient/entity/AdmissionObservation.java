package com.petcare.module.inpatient.entity;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

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

    /** {@code temperature_c NUMERIC(4,1)}: sau làm tròn một chữ số lẻ, giá trị tuyệt đối dưới 1000, tức {@code |t| < 999.95}. */
    private static final BigDecimal TEMPERATURE_BOUND = new BigDecimal("999.95");
    /** {@code |t| < 0.05} làm tròn về 0.0. */
    private static final BigDecimal TEMPERATURE_ZERO_BOUND = new BigDecimal("0.05");
    /** Nhỏ hơn 0.005 làm tròn về 0.00 (BR-KH-04: cân phải dương). */
    private static final BigDecimal WEIGHT_MIN = new BigDecimal("0.005");
    /** {@code weight_kg NUMERIC(6,2)}: sau làm tròn tối đa 9999.99, tức {@code w < 9999.995}. */
    private static final BigDecimal WEIGHT_BOUND = new BigDecimal("9999.995");
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
        invalidReason(temperatureC, heartRate, respiratoryRate, weightKg, appetite, note).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        AdmissionObservation entity = new AdmissionObservation();
        entity.admissionId = requireNonNull(admission.getId(), "admission.id");
        entity.recordedBy = requireNonNull(recordedBy, "recordedBy");
        entity.temperatureC = temperatureC == null ? null : roundTemperature(temperatureC);
        entity.heartRate = heartRate == null ? null : heartRate.shortValue();
        entity.respiratoryRate = respiratoryRate == null ? null : respiratoryRate.shortValue();
        entity.weightKg = weightKg == null ? null : weightKg.setScale(2, RoundingMode.HALF_UP);
        entity.appetite = appetite;
        entity.note = InpatientText.optional(note, InpatientText.CONTENT_MAX_LENGTH, "note");
        entity.abnormal = abnormal;
        return entity;
    }

    /**
     * Một nguồn cho mọi giới hạn của một mục theo dõi (BR-LT-16; docs/adr/0149 mục 35): service ném 400 {@code BR-LT-16}
     * với câu trả về, {@link #record} ném {@link IllegalArgumentException}. Trả rỗng khi hợp lệ. Giới hạn chỉ là giới hạn
     * lưu trữ của cột V38 và BR-KH-04; khoảng lâm sàng chưa chốt (inpatient-v1 A4).
     * <p>
     * <b>So biên bằng {@code compareTo} trước, không {@code setScale} trước:</b> {@code 1e-999999999} là một JSON ngắn nhưng
     * {@code setScale(2)} của nó phải dựng một số hàng tỷ chữ số. Biên làm tròn {@code HALF_UP}: cân hợp lệ khi
     * {@code 0.005 ≤ w < 9999.995} (khớp {@code PetWeightApiService}: sau làm tròn trong 0.01…9999.99), nhiệt độ khi
     * {@code |t| < 999.95} (sau làm tròn không chạm 1000.0).
     */
    public static Optional<String> invalidReason(BigDecimal temperatureC, Integer heartRate, Integer respiratoryRate,
            BigDecimal weightKg, Appetite appetite, String note) {
        String text = note == null ? null : note.strip();
        if (text != null && text.length() > InpatientText.CONTENT_MAX_LENGTH) {
            return Optional.of("Ghi chú tối đa " + InpatientText.CONTENT_MAX_LENGTH + " ký tự");
        }
        if (temperatureC == null && heartRate == null && respiratoryRate == null && weightKg == null
                && appetite == null && (text == null || text.isEmpty())) {
            return Optional.of("Vui lòng nhập ít nhất một chỉ số hoặc ghi chú theo dõi");
        }
        if (temperatureC != null && temperatureC.abs().compareTo(TEMPERATURE_BOUND) >= 0) {
            return Optional.of("Nhiệt độ vượt giới hạn lưu trữ");
        }
        if (isOutOfRange(heartRate)) {
            return Optional.of("Nhịp tim phải từ 1 đến " + SMALLINT_MAX);
        }
        if (isOutOfRange(respiratoryRate)) {
            return Optional.of("Nhịp thở phải từ 1 đến " + SMALLINT_MAX);
        }
        if (weightKg != null) {
            if (weightKg.compareTo(WEIGHT_MIN) < 0) {
                // gồm ≤ 0 (BR-KH-04) và 0.001 → 0.00 sau làm tròn
                return Optional.of("Cân nặng phải lớn hơn 0");
            }
            if (weightKg.compareTo(WEIGHT_BOUND) >= 0) {
                return Optional.of("Cân nặng vượt giới hạn lưu trữ");
            }
        }
        return Optional.empty();
    }

    /** {@code |t| < 0.05} làm tròn về 0.0 mà không {@code setScale} một số có scale cực lớn. */
    private static BigDecimal roundTemperature(BigDecimal temperatureC) {
        if (temperatureC.abs().compareTo(TEMPERATURE_ZERO_BOUND) < 0) {
            return new BigDecimal("0.0");
        }
        return temperatureC.setScale(1, RoundingMode.HALF_UP);
    }

    private static boolean isOutOfRange(Integer rate) {
        return rate != null && (rate < 1 || rate > SMALLINT_MAX);
    }
}
