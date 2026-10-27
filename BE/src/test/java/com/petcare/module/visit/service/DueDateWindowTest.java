package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** {@link DueDateWindow} (docs/adr/0082): cả hai cận đều tính; ngày đã qua và ngày sau cận trên không nhắc. */
class DueDateWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    void windowIsTodayThroughTodayPlusDays() {
        DueDateWindow window = DueDateWindow.at(TODAY, 7);

        assertThat(window.from()).isEqualTo(TODAY);
        assertThat(window.to()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void bothBoundsAreInsideAndOutsideIsNot() {
        DueDateWindow window = DueDateWindow.at(TODAY, 7);

        assertThat(window.contains(TODAY.minusDays(1))).isFalse();
        assertThat(window.contains(TODAY)).isTrue();
        assertThat(window.contains(TODAY.plusDays(7))).isTrue();
        assertThat(window.contains(TODAY.plusDays(8))).isFalse();
    }

    @Test
    void nonPositiveDaysRejected() {
        assertThatThrownBy(() -> DueDateWindow.at(TODAY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DueDateWindow.at(TODAY, -3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyWindowRejected() {
        assertThatThrownBy(() -> new DueDateWindow(TODAY, TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
