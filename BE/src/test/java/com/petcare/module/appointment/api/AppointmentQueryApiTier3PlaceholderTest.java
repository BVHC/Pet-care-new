package com.petcare.module.appointment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Placeholder tầng 3 của appointment (T55b, docs/06 §6b hàng 13, 15; docs/adr/0113). */
class AppointmentQueryApiTier3PlaceholderTest {

    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void countBookedByBranchThrowsUntilImplemented() {
        assertThatThrownBy(() -> appointments.countBookedByBranch(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("AppointmentQueryApi.countBookedByBranch")
                .hasMessageContaining("T75");
    }

    @Test
    void upcomingBookedCarriesTheSevenFieldsOfSpec07I10() {
        AppointmentQueryApi.UpcomingBooked row = new AppointmentQueryApi.UpcomingBooked(1L, "LH-261120-0001", 2L, 3L,
                4L, LocalDate.of(2026, 11, 20), LocalTime.of(9, 30));

        assertThat(row.appointmentId()).isEqualTo(1L);
        assertThat(row.code()).isEqualTo("LH-261120-0001");
        assertThat(row.petId()).isEqualTo(2L);
        assertThat(row.serviceId()).isEqualTo(3L);
        assertThat(row.branchId()).isEqualTo(4L);
        assertThat(row.slotDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(row.slotStart()).isEqualTo(LocalTime.of(9, 30));
    }
}
