package com.petcare.module.inpatient.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Bất biến tự thân của {@link Admission} (docs/adr/0149 §1b A1–A12): lớp chặn cuối khi service sót. Mốc thời gian dùng
 * giờ Việt Nam (UTC+7): 2043-03-14T02:00Z = 09:00 ngày 14; 2043-03-14T17:30Z = 00:30 ngày 15.
 */
class AdmissionTest {

    private static final Instant ADMITTED_AT = Instant.parse("2043-03-14T02:00:00Z");

    private static Admission fromVisit() {
        return Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 200_000L, 7L, ADMITTED_AT);
    }

    private static Admission fromBoarding() {
        return Admission.admit("NT-4303-00002", 1L, 2L, 3L, 8L, null, 5L, 200_000L, 7L, ADMITTED_AT);
    }

    @Test
    void admitFromVisitKeepsEveryColumnAndStartsAdmitted() {
        Admission a = fromVisit();

        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(a.getCode()).isEqualTo("NT-4303-00001");
        assertThat(a.getKennelId()).isEqualTo(9L);
        assertThat(a.getBoardingBookingId()).isNull();
        assertThat(a.getUnitPrice()).isEqualTo(200_000L);
        assertThat(a.getAdmittedBy()).isEqualTo(7L);
        assertThat(a.getAdmittedAt()).isEqualTo(ADMITTED_AT);
        assertThat(a.getDischargeOutcome()).isNull();
        assertThat(a.getOrderLineId()).isNull();
    }

    @Test
    void admitFromBoardingHasBookingAndNoKennel() {
        Admission a = fromBoarding();

        assertThat(a.getBoardingBookingId()).isEqualTo(8L);
        assertThat(a.getKennelId()).isNull();
    }

    @Test
    void admitRejectsBothSourcesAndNoSource() {
        assertThatThrownBy(() -> Admission.admit("NT-4303-00001", 1L, 2L, 3L, 8L, 9L, 5L, 1L, 7L, ADMITTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, null, 5L, 1L, 7L, ADMITTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void admitRejectsNegativePriceButAcceptsZero() {
        assertThatThrownBy(() -> Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, -1L, 7L, ADMITTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 0L, 7L, ADMITTED_AT).getUnitPrice())
                .isZero();
    }

    @Test
    void admitRejectsBlankOrTooLongCodeAndMissingColumns() {
        assertThatThrownBy(() -> Admission.admit("  ", 1L, 2L, 3L, null, 9L, 5L, 1L, 7L, ADMITTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admission.admit("N".repeat(21), 1L, 2L, 3L, null, 9L, 5L, 1L, 7L, ADMITTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admission.admit("NT-4303-00001", null, 2L, 3L, null, 9L, 5L, 1L, 7L, ADMITTED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 1L, 7L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dischargeFillsEveryDischargeColumnAndTrimsSummary() {
        Admission a = fromVisit();
        Instant out = ADMITTED_AT.plusSeconds(3600);

        a.discharge(DischargeOutcome.HOME, "  Ổn định, về nhà  ", 7L, out, 55L, 0);

        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(a.getDischargeOutcome()).isEqualTo(DischargeOutcome.HOME);
        assertThat(a.getDischargeSummary()).isEqualTo("Ổn định, về nhà");
        assertThat(a.getDischargedBy()).isEqualTo(7L);
        assertThat(a.getDischargedAt()).isEqualTo(out);
        assertThat(a.getOrderLineId()).isEqualTo(55L);
    }

    @Test
    void dischargeAtTheSameInstantAsAdmissionIsAllowed() {
        Admission a = fromVisit();

        a.discharge(DischargeOutcome.DECEASED, "Thú mất", 7L, ADMITTED_AT, 55L, 0);

        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
    }

    @Test
    void dischargeTwiceIsRejectedAndKeepsTheFirstResult() {
        Admission a = fromVisit();
        a.discharge(DischargeOutcome.HOME, "Lần một", 7L, ADMITTED_AT.plusSeconds(60), 55L, 0);

        assertThatThrownBy(() -> a.discharge(DischargeOutcome.DECEASED, "Lần hai", 8L, ADMITTED_AT.plusSeconds(120),
                56L, 0)).isInstanceOf(IllegalStateException.class);

        assertThat(a.getDischargeOutcome()).isEqualTo(DischargeOutcome.HOME);
        assertThat(a.getDischargeSummary()).isEqualTo("Lần một");
        assertThat(a.getOrderLineId()).isEqualTo(55L);
    }

    @Test
    void dischargeRejectsBlankSummaryIncludingUnicodeWhitespace() {
        Admission a = fromVisit();

        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, " 　 ", 7L, ADMITTED_AT, 55L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, null, 7L, ADMITTED_AT, 55L, 0))
                .isInstanceOf(NullPointerException.class);
        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
    }

    @Test
    void dischargeBeforeAdmissionIsRejectedAndWritesNothing() {
        Admission a = fromVisit();

        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, "Xong", 7L, ADMITTED_AT.minusSeconds(1), 55L, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(a.getDischargedAt()).isNull();
        assertThat(a.getOrderLineId()).isNull();
    }

    @Test
    void dischargeIsBlockedWhileOrdersAreStillOrdered() {
        Admission a = fromVisit();

        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, "Xong", 7L, ADMITTED_AT, 55L, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, "Xong", 7L, ADMITTED_AT, 55L, -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
    }

    @Test
    void dischargeRequiresOutcomeActorAndOrderLine() {
        Admission a = fromVisit();

        assertThatThrownBy(() -> a.discharge(null, "Xong", 7L, ADMITTED_AT, 55L, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, "Xong", null, ADMITTED_AT, 55L, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> a.discharge(DischargeOutcome.HOME, "Xong", 7L, ADMITTED_AT, null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThat(a.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
    }

    @Test
    void requireAdmittedPassesWhileAdmittedAndFailsAfterDischarge() {
        Admission a = fromVisit();
        a.requireAdmitted();

        a.discharge(DischargeOutcome.HOME, "Xong", 7L, ADMITTED_AT, 55L, 0);

        assertThatThrownBy(a::requireAdmitted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void billedDaysIsOneOnTheAdmissionDay() {
        assertThat(fromVisit().billedDays(ADMITTED_AT)).isEqualTo(1);
        assertThat(fromVisit().billedDays(Instant.parse("2043-03-14T16:59:59Z"))).isEqualTo(1);
    }

    @Test
    void billedDaysCrossesMidnightInVietnamTimeNotUtc() {
        // 17:30Z ngày 14 là 00:30 ngày 15 giờ Việt Nam: đã sang ngày thứ hai
        assertThat(fromVisit().billedDays(Instant.parse("2043-03-14T17:30:00Z"))).isEqualTo(2);
        assertThat(fromVisit().billedDays(Instant.parse("2043-03-16T02:00:00Z"))).isEqualTo(3);
    }

    @Test
    void billedDaysNeverDropsBelowOneWhenClockIsBehindAdmission() {
        assertThat(fromVisit().billedDays(ADMITTED_AT.minusSeconds(86_400L * 3))).isEqualTo(1);
    }

    @Test
    void billedDaysIsFrozenAtDischargeEvenWhenNowKeepsMoving() {
        Admission a = fromVisit();
        a.discharge(DischargeOutcome.HOME, "Xong", 7L, Instant.parse("2043-03-14T17:30:00Z"), 55L, 0);

        assertThat(a.billedDays(Instant.parse("2043-03-14T17:30:00Z"))).isEqualTo(2);
        assertThat(a.billedDays(Instant.parse("2043-06-01T00:00:00Z"))).isEqualTo(2);
        assertThat(a.billedDays(null)).isEqualTo(2);
    }

    @Test
    void estimatedAmountIsBilledDaysTimesUnitPrice() {
        Admission a = fromVisit();

        assertThat(a.estimatedAmount(ADMITTED_AT)).isEqualTo(200_000L);
        assertThat(a.estimatedAmount(Instant.parse("2043-03-16T02:00:00Z"))).isEqualTo(600_000L);
    }

    @Test
    void estimatedAmountFailsLoudlyInsteadOfOverflowing() {
        Admission a = Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, Long.MAX_VALUE, 7L, ADMITTED_AT);

        assertThat(a.estimatedAmount(ADMITTED_AT)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> a.estimatedAmount(Instant.parse("2043-03-15T02:00:00Z")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nowIsRequiredWhileAdmitted() {
        assertThatThrownBy(() -> fromVisit().billedDays(null)).isInstanceOf(NullPointerException.class);
    }
}
