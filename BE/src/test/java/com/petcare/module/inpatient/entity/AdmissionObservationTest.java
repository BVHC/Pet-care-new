package com.petcare.module.inpatient.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.Column;

/** Bất biến tự thân của {@link AdmissionObservation} (docs/adr/0149 §1b B1–B7). */
class AdmissionObservationTest {

    private static final Instant AT = Instant.parse("2043-03-14T02:00:00Z");

    private static Admission admitted() {
        Admission a = Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 200_000L, 7L, AT);
        ReflectionTestUtils.setField(a, "id", 10L);
        return a;
    }

    private static AdmissionObservation noteOnly(String note) {
        return AdmissionObservation.record(admitted(), 7L, null, null, null, null, null, note, false);
    }

    @Test
    void recordKeepsEveryMeasurementAndRoundsToTheColumnScale() {
        AdmissionObservation o = AdmissionObservation.record(admitted(), 7L, new BigDecimal("38.56"), 120, 30,
                new BigDecimal("5.555"), Appetite.REDUCED, "  Ăn ít  ", true);

        assertThat(o.getAdmissionId()).isEqualTo(10L);
        assertThat(o.getRecordedBy()).isEqualTo(7L);
        assertThat(o.getTemperatureC()).isEqualByComparingTo("38.6");
        assertThat(o.getTemperatureC().scale()).isEqualTo(1);
        assertThat(o.getHeartRate()).isEqualTo((short) 120);
        assertThat(o.getRespiratoryRate()).isEqualTo((short) 30);
        assertThat(o.getWeightKg()).isEqualByComparingTo("5.56");
        assertThat(o.getWeightKg().scale()).isEqualTo(2);
        assertThat(o.getAppetite()).isEqualTo(Appetite.REDUCED);
        assertThat(o.getNote()).isEqualTo("Ăn ít");
        assertThat(o.isAbnormal()).isTrue();
    }

    @Test
    void anySingleMeasurementOrANoteIsEnough() {
        assertThat(AdmissionObservation.record(admitted(), 7L, new BigDecimal("39.0"), null, null, null, null, null,
                false).getTemperatureC()).isNotNull();
        assertThat(AdmissionObservation.record(admitted(), 7L, null, 100, null, null, null, null, false)
                .getHeartRate()).isNotNull();
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, 20, null, null, null, false)
                .getRespiratoryRate()).isNotNull();
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, BigDecimal.ONE, null, null, false)
                .getWeightKg()).isNotNull();
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, null, Appetite.NONE, null, false)
                .getAppetite()).isEqualTo(Appetite.NONE);
        assertThat(noteOnly("Nằm yên").getNote()).isEqualTo("Nằm yên");
    }

    @Test
    void anObservationWithNothingInItIsRejected() {
        assertThatThrownBy(() -> noteOnly(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noteOnly("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noteOnly(" 　 ")).isInstanceOf(IllegalArgumentException.class);
        // chỉ cờ bất thường không đủ: phải có nội dung để báo
        assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, null, null, null, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightMustBePositiveAndFitTheColumn() {
        for (String bad : new String[] {"0", "-1", "0.001", "10000", "99999.99"}) {
            assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, null, null, null,
                    new BigDecimal(bad), null, null, false)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, new BigDecimal("9999.99"), null, null,
                false).getWeightKg()).isEqualByComparingTo("9999.99");
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, new BigDecimal("0.01"), null, null,
                false).getWeightKg()).isEqualByComparingTo("0.01");
    }

    @Test
    void temperatureMustFitTheColumn() {
        assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, new BigDecimal("1000"), null, null, null,
                null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, new BigDecimal("-1000.0"), null, null,
                null, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AdmissionObservation.record(admitted(), 7L, new BigDecimal("999.9"), null, null, null, null, null,
                false).getTemperatureC()).isEqualByComparingTo("999.9");
    }

    @Test
    void heartAndRespiratoryRateMustBePositiveAndFitSmallint() {
        for (int bad : new int[] {0, -5, 32_768}) {
            assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, null, bad, null, null, null, null,
                    false)).as("heartRate " + bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AdmissionObservation.record(admitted(), 7L, null, null, bad, null, null, null,
                    false)).as("respiratoryRate " + bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AdmissionObservation.record(admitted(), 7L, null, 32_767, null, null, null, null, false)
                .getHeartRate()).isEqualTo(Short.MAX_VALUE);
    }

    @Test
    void noteIsLimitedTo500Characters() {
        assertThat(noteOnly("x".repeat(500)).getNote()).hasSize(500);
        assertThatThrownBy(() -> noteOnly("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordIsRejectedForADischargedAdmission() {
        Admission a = admitted();
        a.discharge(DischargeOutcome.HOME, "Xong", 7L, AT, 55L, 0);

        assertThatThrownBy(() -> AdmissionObservation.record(a, 7L, null, null, null, null, null, "Ghi chú", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordNeedsAnAdmissionThatHasBeenSavedAndARecorder() {
        Admission unsaved = Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 1L, 7L, AT);

        assertThatThrownBy(() -> AdmissionObservation.record(unsaved, 7L, null, null, null, null, null, "Ghi chú",
                false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AdmissionObservation.record(admitted(), null, null, null, null, null, null, "Ghi chú",
                false)).isInstanceOf(NullPointerException.class);
    }

    /** LOG chỉ thêm (BR-LT-16): mọi cột ánh xạ đều {@code updatable = false}, và không có setter / method đổi. */
    @Test
    void everyMappedColumnIsNotUpdatable() {
        for (Field field : AdmissionObservation.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertThat(column.updatable()).as(field.getName()).isFalse();
            }
        }
        assertThat(AdmissionObservation.class.getDeclaredMethods())
                .filteredOn(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .extracting(java.lang.reflect.Method::getName)
                .allMatch(name -> name.startsWith("get") || name.startsWith("is") || name.equals("record")
                        || name.equals("invalidReason"));
    }

    // ---- invalidReason: một nguồn cho giới hạn (docs/adr/0149 mục 35) ----

    private static java.util.Optional<String> reason(BigDecimal temperature, Integer heart, Integer respiratory,
            BigDecimal weight, Appetite appetite, String note) {
        return AdmissionObservation.invalidReason(temperature, heart, respiratory, weight, appetite, note);
    }

    @Test
    void invalidReasonIsEmptyForAValidObservationAndMatchesWhatRecordAccepts() {
        assertThat(reason(new BigDecimal("38.6"), 120, 30, new BigDecimal("5.55"), Appetite.NONE, "Ổn")).isEmpty();
        assertThat(reason(null, null, null, null, null, "Nằm yên")).isEmpty();
        assertThat(reason(null, null, null, null, Appetite.REDUCED, null)).isEmpty();
    }

    @Test
    void invalidReasonExplainsEachFailureInVietnamese() {
        assertThat(reason(null, null, null, null, null, null)).hasValueSatisfying(r -> assertThat(r).contains("ít nhất"));
        assertThat(reason(null, null, null, null, null, " 　 ")).isPresent();
        assertThat(reason(new BigDecimal("1000"), null, null, null, null, null)).hasValueSatisfying(
                r -> assertThat(r).startsWith("Nhiệt độ"));
        assertThat(reason(null, 0, null, null, null, null)).hasValueSatisfying(r -> assertThat(r).startsWith("Nhịp tim"));
        assertThat(reason(null, null, 32_768, null, null, null)).hasValueSatisfying(
                r -> assertThat(r).startsWith("Nhịp thở"));
        assertThat(reason(null, null, null, BigDecimal.ZERO, null, null)).hasValueSatisfying(
                r -> assertThat(r).startsWith("Cân nặng"));
        assertThat(reason(null, null, null, null, null, "x".repeat(501))).hasValueSatisfying(
                r -> assertThat(r).contains("500"));
    }

    @Test
    void weightBoundsFollowTheRoundedColumnValue() {
        for (String ok : new String[] {"0.005", "0.01", "9999.99", "9999.994"}) {
            assertThat(reason(null, null, null, new BigDecimal(ok), null, null)).as(ok).isEmpty();
        }
        for (String bad : new String[] {"0.00499", "0", "-0.01", "9999.995", "10000"}) {
            assertThat(reason(null, null, null, new BigDecimal(bad), null, null)).as(bad).isPresent();
        }
        // 0.005 làm tròn HALF_UP thành 0.01 và 9999.994 thành 9999.99: khớp PetWeightApiService (0.01…9999.99)
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, new BigDecimal("0.005"), null, null,
                false).getWeightKg()).isEqualByComparingTo("0.01");
        assertThat(AdmissionObservation.record(admitted(), 7L, null, null, null, new BigDecimal("9999.994"), null,
                null, false).getWeightKg()).isEqualByComparingTo("9999.99");
    }

    @Test
    void temperatureBoundsFollowTheRoundedColumnValue() {
        for (String ok : new String[] {"0", "-0.04", "38.55", "999.94", "-999.94"}) {
            assertThat(reason(new BigDecimal(ok), null, null, null, null, null)).as(ok).isEmpty();
        }
        for (String bad : new String[] {"999.95", "-999.95", "1000", "-1000.0"}) {
            assertThat(reason(new BigDecimal(bad), null, null, null, null, null)).as(bad).isPresent();
        }
        assertThat(AdmissionObservation.record(admitted(), 7L, new BigDecimal("0.04"), null, null, null, null, "x",
                false).getTemperatureC()).isEqualByComparingTo("0.0");
        assertThat(AdmissionObservation.record(admitted(), 7L, new BigDecimal("38.55"), null, null, null, null, "x",
                false).getTemperatureC()).isEqualByComparingTo("38.6");
    }

    /**
     * {@code 1e-100000000} là JSON ngắn nhưng {@code setScale(2)} của nó chạy hàng giây (1e-10000000 mất ~4 s) hoặc ném
     * {@code ArithmeticException} (1e-999999999 → 500): giới hạn phải so bằng {@code compareTo} trước (D081 là bản lỗi
     * cùng gốc ở customer).
     */
    @Test
    void anAbsurdExponentIsAnsweredQuicklyWithoutRoundingFirst() {
        BigDecimal tiny = new BigDecimal("1e-100000000");
        BigDecimal huge = new BigDecimal("1e999999999");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertThat(reason(null, null, null, tiny, null, null)).hasValueSatisfying(
                    r -> assertThat(r).startsWith("Cân nặng"));
            assertThat(reason(null, null, null, huge, null, null)).hasValueSatisfying(
                    r -> assertThat(r).startsWith("Cân nặng"));
            assertThat(reason(huge, null, null, null, null, null)).hasValueSatisfying(
                    r -> assertThat(r).startsWith("Nhiệt độ"));
            // nhiệt độ gần 0 hợp lệ và làm tròn về 0.0 mà không setScale số khổng lồ
            assertThat(reason(tiny, null, null, null, null, null)).isEmpty();
            assertThat(AdmissionObservation.record(admitted(), 7L, tiny, null, null, null, null, "x", false)
                    .getTemperatureC()).isEqualByComparingTo("0.0");
        });
    }
}
