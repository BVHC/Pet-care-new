package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.catalog.api.ServiceGroup;

/**
 * {@link AppointmentActions} (docs/adr/0043): {@code canCancel} = BOOKED và chưa tới giờ hẹn (BR-LH-07);
 * {@code canReschedule} thêm còn lượt đổi [CFG] và — với khách — còn ít nhất 12 giờ [CFG], biên tính là được (BR-LH-06).
 */
class AppointmentActionsTest {

    /** Khung 2026-10-16 09:00 giờ Việt Nam = 02:00Z. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final Instant SLOT_START = Instant.parse("2026-10-16T02:00:00Z");

    @Test
    void customerNeedsTwelveHoursAndTheBoundaryCounts() {
        Appointment booked = appointment(AppointmentStatus.BOOKED, 0);

        assertThat(flags(booked, true, SLOT_START.minusSeconds(12 * 3600)))
                .isEqualTo(new AppointmentActions.Flags(true, true));
        assertThat(flags(booked, true, SLOT_START.minusSeconds(12 * 3600 - 1)))
                .isEqualTo(new AppointmentActions.Flags(false, true));
    }

    @Test
    void receptionistMayRescheduleUntilTheSlotStarts() {
        Appointment booked = appointment(AppointmentStatus.BOOKED, 0);

        assertThat(flags(booked, false, SLOT_START.minusSeconds(1)))
                .isEqualTo(new AppointmentActions.Flags(true, true));
        assertThat(flags(booked, false, SLOT_START)).isEqualTo(new AppointmentActions.Flags(false, false));
    }

    @Test
    void cancelEndsWhenTheSlotStartsForBothRoles() {
        Appointment booked = appointment(AppointmentStatus.BOOKED, 3);

        assertThat(flags(booked, true, SLOT_START.minusSeconds(1)).canCancel()).isTrue();
        assertThat(flags(booked, true, SLOT_START).canCancel()).isFalse();
        assertThat(flags(booked, false, SLOT_START.plusSeconds(60)).canCancel()).isFalse();
    }

    @Test
    void noTurnsLeftBlocksRescheduleOnly() {
        Instant early = SLOT_START.minusSeconds(48 * 3600);

        assertThat(flags(appointment(AppointmentStatus.BOOKED, 2), true, early).canReschedule()).isTrue();
        assertThat(flags(appointment(AppointmentStatus.BOOKED, 3), true, early))
                .isEqualTo(new AppointmentActions.Flags(false, true));
        assertThat(flags(appointment(AppointmentStatus.BOOKED, 3), false, early).canReschedule()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = "BOOKED", mode = EnumSource.Mode.EXCLUDE)
    void onlyBookedAppointmentsHaveActions(AppointmentStatus status) {
        Instant early = SLOT_START.minusSeconds(48 * 3600);

        assertThat(flags(appointment(status, 0), true, early)).isEqualTo(new AppointmentActions.Flags(false, false));
        assertThat(flags(appointment(status, 0), false, early)).isEqualTo(new AppointmentActions.Flags(false, false));
    }

    private static AppointmentActions.Flags flags(Appointment appointment, boolean customer, Instant now) {
        return AppointmentActions.forViewer(appointment, customer, now, 3, 12);
    }

    private static Appointment appointment(AppointmentStatus status, int rescheduleCount) {
        Appointment appointment = Appointment.book("LH-261015-0001", 1L, 2L, 3L, 4L, ServiceGroup.MEDICAL, DAY, START,
                AppointmentChannel.ONLINE, 5L, null);
        set(appointment, "status", status);
        set(appointment, "rescheduleCount", (short) rescheduleCount);
        return appointment;
    }

    private static void set(Appointment appointment, String field, Object value) {
        try {
            Field f = Appointment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(appointment, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
