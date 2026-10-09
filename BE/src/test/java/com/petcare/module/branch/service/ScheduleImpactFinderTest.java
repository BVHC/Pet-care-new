package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.BookedStay;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.module.branch.service.ScheduleImpactFinder.Impact;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;

/** Lịch hẹn và đặt chỗ bị ảnh hưởng khi đổi giờ hoặc thêm ngày nghỉ (BR-CN-03, BR-CN-04, BR-LH-10). */
@ExtendWith(MockitoExtension.class)
class ScheduleImpactFinderTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 19);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 20);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 25);

    @Mock AppointmentQueryApi appointments;
    @Mock BoardingQueryApi boardings;
    @Mock PetQueryApi pets;
    @Mock CustomerQueryApi customers;

    ScheduleImpactFinder finder;

    @BeforeEach
    void setUp() {
        finder = new ScheduleImpactFinder(appointments, boardings, pets, customers);
    }

    private static BookedSlot slot(long id, LocalDate date, String start) {
        return new BookedSlot(id, "LH-" + id, 3L, 5L, 6L, date, LocalTime.parse(start));
    }

    private static BookedStay stay(long id, LocalDate checkIn, LocalDate checkOut) {
        return new BookedStay(id, "LT-" + id, 5L, 6L, checkIn, checkOut);
    }

    private static List<TimeRange> morning() {
        return List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)));
    }

    /** Thứ Hai–Thứ Bảy chỉ mở buổi sáng, Chủ nhật nghỉ. */
    private static BranchSchedule mornings() {
        return new BranchSchedule(List.of(new Version(LocalDate.of(2026, 1, 1),
                Map.of(1, morning(), 2, morning(), 3, morning(), 4, morning(), 5, morning(), 6, morning()))), Set.of());
    }

    @Test
    void hoursChangeKeepsOnlyBookingsThatNoLongerFit() {
        when(appointments.findBookedFrom(3L, MON)).thenReturn(List.of(
                slot(1, MON, "09:00"), slot(2, MON, "15:00"), slot(3, SUN, "09:00")));
        when(boardings.findBookedFrom(3L, MON)).thenReturn(List.of(
                stay(10, MON, TUE), stay(11, TUE, SUN)));

        Impact impact = finder.forHoursChange(3L, mornings(), MON);

        assertThat(impact.appointments()).extracting(BookedSlot::appointmentId).containsExactly(2L, 3L);
        assertThat(impact.stays()).extracting(BookedStay::bookingId).containsExactly(11L);
        assertThat(impact.isEmpty()).isFalse();
    }

    @Test
    void hoursChangeWithNothingAffectedIsEmpty() {
        when(appointments.findBookedFrom(3L, MON)).thenReturn(List.of(slot(1, MON, "09:00")));
        when(boardings.findBookedFrom(3L, MON)).thenReturn(List.of(stay(10, MON, TUE)));

        assertThat(finder.forHoursChange(3L, mornings(), MON).isEmpty()).isTrue();
    }

    @Test
    void holidayKeepsAppointmentsOnThatDayAndStaysCheckingInOrOutThatDay() {
        when(appointments.findBookedFrom(3L, TUE)).thenReturn(List.of(
                slot(1, TUE, "09:00"), slot(2, TUE.plusDays(1), "09:00")));
        when(boardings.findBookedFrom(3L, TUE)).thenReturn(List.of(
                stay(10, MON, TUE),                  // trả đúng ngày nghỉ
                stay(11, TUE, TUE.plusDays(2)),      // nhận đúng ngày nghỉ
                stay(12, MON, TUE.plusDays(2)),      // ở qua ngày nghỉ, không nhận / trả: không ảnh hưởng
                stay(13, TUE.plusDays(1), TUE.plusDays(3))));

        Impact impact = finder.forHoliday(3L, TUE);

        assertThat(impact.appointments()).extracting(BookedSlot::appointmentId).containsExactly(1L);
        assertThat(impact.stays()).extracting(BookedStay::bookingId).containsExactly(10L, 11L);
    }

    @Test
    void describeFillsNamesAndPhonesAndCachesLookups() {
        when(pets.findPet(5L)).thenReturn(Optional.of(
                new PetSummary(5L, 6L, "Mực", Species.DOG, null, null, null)));
        when(customers.findContact(6L)).thenReturn(Optional.of(
                new CustomerContact(6L, "Nguyễn Văn A", "0901234567", null, null, false)));
        Impact impact = new Impact(List.of(slot(1, MON, "15:00"), slot(2, MON, "15:30")),
                List.of(stay(10, MON, SUN)));

        ScheduleImpact described = finder.describe(impact);

        assertThat(described.appointments()).hasSize(2);
        assertThat(described.appointments().get(0).code()).isEqualTo("LH-1");
        assertThat(described.appointments().get(0).petName()).isEqualTo("Mực");
        assertThat(described.appointments().get(0).customerName()).isEqualTo("Nguyễn Văn A");
        assertThat(described.appointments().get(0).customerPhone()).isEqualTo("0901234567");
        assertThat(described.boardingBookings().get(0).code()).isEqualTo("LT-10");
        assertThat(described.boardingBookings().get(0).checkOutDate()).isEqualTo(SUN);
        verify(pets, times(1)).findPet(5L);
        verify(customers, times(1)).findContact(6L);
    }

    @Test
    void describeSurvivesAMissingPetOrCustomer() {
        when(pets.findPet(5L)).thenReturn(Optional.empty());
        when(customers.findContact(6L)).thenReturn(Optional.empty());

        ScheduleImpact described = finder.describe(new Impact(List.of(slot(1, MON, "15:00")), List.of()));

        assertThat(described.appointments().get(0).petName()).isEqualTo("(không rõ)");
        assertThat(described.appointments().get(0).customerName()).isEqualTo("(không rõ)");
        assertThat(described.appointments().get(0).customerPhone()).isNull();
    }
}
