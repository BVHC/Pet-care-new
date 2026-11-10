package com.petcare.module.visit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;

/**
 * Nhóm tuổi theo 07 §7.3. Ranh giới tính bằng NGÀY: 52 tuần = 364 ngày, không phải một năm lịch. Visit và boarding cùng
 * dùng {@link AgeGroup#of}, nên các ranh giới này là của cả hai (AI-0).
 */
class AgeGroupTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final int SENIOR_YEARS = 7;
    private static final long SENIOR_DAYS = SENIOR_YEARS * 364L;

    private static AgeGroup ageOfDays(long days) {
        return AgeGroup.of(TODAY.minusDays(days), TODAY, SENIOR_YEARS);
    }

    @Test
    void under52WeeksIsJuniorAndExactly52WeeksIsAdult() {
        assertThat(ageOfDays(363)).isEqualTo(AgeGroup.JUNIOR);
        assertThat(ageOfDays(364)).isEqualTo(AgeGroup.ADULT);
    }

    @Test
    void aFullCalendarYearIsAlreadyAdultNotJunior() {
        assertThat(AgeGroup.of(TODAY.minusYears(1), TODAY, SENIOR_YEARS)).isEqualTo(AgeGroup.ADULT);
        assertThat(AgeGroup.of(LocalDate.of(2025, 11, 10), TODAY, SENIOR_YEARS)).as("364 ngày lịch").isEqualTo(AgeGroup.ADULT);
    }

    @Test
    void seniorStartsAtThresholdTimes52WeeksNotThresholdCalendarYears() {
        assertThat(ageOfDays(SENIOR_DAYS - 1)).isEqualTo(AgeGroup.ADULT);
        assertThat(ageOfDays(SENIOR_DAYS)).isEqualTo(AgeGroup.SENIOR);
        assertThat(ageOfDays(SENIOR_DAYS + 500)).isEqualTo(AgeGroup.SENIOR);
    }

    @Test
    void missingBirthDateIsUnknown() {
        assertThat(AgeGroup.of(null, TODAY, SENIOR_YEARS)).isEqualTo(AgeGroup.UNKNOWN);
    }

    @Test
    void aBornTodayOrFutureBirthDateIsJunior() {
        assertThat(ageOfDays(0)).isEqualTo(AgeGroup.JUNIOR);
        assertThat(ageOfDays(-5)).isEqualTo(AgeGroup.JUNIOR);
    }
}
