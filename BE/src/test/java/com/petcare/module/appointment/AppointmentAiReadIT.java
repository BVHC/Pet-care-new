package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.UpcomingBooked;

/** I-10 (07 §11, BR-AI-27, AI-0): lịch {@code BOOKED} sắp tới của khách, trên Postgres thật. */
class AppointmentAiReadIT extends AiReadItSupport {

    @Autowired private AppointmentQueryApi appointments;

    @Test
    void upcomingAreBookedOnlyFromNowInTimeOrderAndOnlyOfThatCustomer() {
        long customer = AiReadFixtures.next();
        long stranger = AiReadFixtures.next();
        long petA = fx.pet(customer, "DOG", null);
        long petB = fx.pet(customer, "CAT", null);
        long strangerPet = fx.pet(stranger, "DOG", null);
        long svc = AiReadFixtures.next();
        LocalDate d = today();
        LocalDateTime now = d.atTime(12, 0);

        long boundary = fx.appointment(customer, petA, svc, "BOOKED", d, LocalTime.of(12, 0));
        fx.appointment(customer, petB, svc, "BOOKED", d, LocalTime.of(11, 59));
        fx.appointment(customer, petA, svc, "BOOKED", d.minusDays(1), LocalTime.of(15, 0));
        long tomorrowEarly = fx.appointment(customer, petB, svc, "BOOKED", d.plusDays(1), LocalTime.of(8, 30));
        long tomorrow = fx.appointment(customer, petA, svc, "BOOKED", d.plusDays(1), LocalTime.of(9, 0));
        long later = fx.appointment(customer, petA, svc, "BOOKED", d.plusDays(3), LocalTime.of(10, 0));
        fx.appointment(customer, petA, svc, "CANCELLED", d.plusDays(2), LocalTime.of(10, 0));
        fx.appointment(customer, petB, svc, "COMPLETED", d.plusDays(2), LocalTime.of(10, 0));
        fx.appointment(stranger, strangerPet, svc, "BOOKED", d.plusDays(1), LocalTime.of(9, 0));

        List<UpcomingBooked> upcoming = appointments.findUpcomingBookedOfCustomer(customer, now, 10);

        assertThat(upcoming).extracting(UpcomingBooked::appointmentId)
                .as("lịch đúng bằng now vẫn sắp tới; lịch quá giờ, đã hủy, đã xong, của khách khác thì không")
                .containsExactly(boundary, tomorrowEarly, tomorrow, later);
        UpcomingBooked first = upcoming.get(0);
        assertThat(first.code()).startsWith("A");
        assertThat(first.petId()).isEqualTo(petA);
        assertThat(first.serviceId()).isEqualTo(svc);
        assertThat(first.branchId()).isEqualTo(999999L);
        assertThat(first.slotDate()).isEqualTo(d);
        assertThat(first.slotStart()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void limitCapsTheListAndANonPositiveLimitIsEmpty() {
        long customer = AiReadFixtures.next();
        long pet = fx.pet(customer, "DOG", null);
        long svc = AiReadFixtures.next();
        LocalDate d = today();
        long soonest = fx.appointment(customer, pet, svc, "BOOKED", d.plusDays(1), LocalTime.of(9, 0));
        long second = fx.appointment(customer, pet, svc, "BOOKED", d.plusDays(2), LocalTime.of(9, 0));
        fx.appointment(customer, pet, svc, "BOOKED", d.plusDays(3), LocalTime.of(9, 0));
        LocalDateTime now = d.atTime(8, 0);

        assertThat(appointments.findUpcomingBookedOfCustomer(customer, now, 2)).extracting(UpcomingBooked::appointmentId)
                .containsExactly(soonest, second);
        assertThat(appointments.findUpcomingBookedOfCustomer(customer, now, 0)).isEmpty();
        assertThat(appointments.findUpcomingBookedOfCustomer(customer, now, -3)).isEmpty();
        assertThat(appointments.findUpcomingBookedOfCustomer(AiReadFixtures.next(), now, 5)).isEmpty();
    }
}
