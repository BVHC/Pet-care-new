package com.petcare.module.report.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.petcare.platform.exception.BusinessRuleViolationException;

/** Kỳ báo cáo (BR-BC-01; docs/adr/0087 G2): from ≤ to, và to nhỏ hơn from + N tháng. */
class ReportPeriodTest {

    private static void check(String from, String to, int maxMonths) {
        ReportPeriod.validate(LocalDate.parse(from), LocalDate.parse(to), maxMonths);
    }

    @Test
    void fromAfterToIsRejectedWithTheRuleId() {
        assertThatThrownBy(() -> check("2026-03-02", "2026-03-01", 12))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BC-01");
    }

    @Test
    void sameDayIsAccepted() {
        assertThatCode(() -> check("2026-03-01", "2026-03-01", 12)).doesNotThrowAnyException();
    }

    @Test
    void exactlyTwelveMonthsInclusiveIsAccepted() {
        assertThatCode(() -> check("2026-01-01", "2026-12-31", 12)).doesNotThrowAnyException();
    }

    @Test
    void oneDayMoreIsRejectedAndNamesTheLimit() {
        assertThatThrownBy(() -> check("2026-01-01", "2027-01-01", 12))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("12 tháng").hasMessageContaining("BR-BC-01");
    }

    @Test
    void monthEndIsClampedByPlusMonths() {
        assertThatCode(() -> check("2026-01-31", "2026-02-27", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> check("2026-01-31", "2026-02-28", 1))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void limitFollowsTheConfiguredMonths() {
        assertThatCode(() -> check("2026-01-01", "2026-06-30", 6)).doesNotThrowAnyException();
        assertThatThrownBy(() -> check("2026-01-01", "2026-07-01", 6))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("6 tháng");
    }
}
