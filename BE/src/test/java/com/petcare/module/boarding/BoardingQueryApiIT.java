package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.BookedStay;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;

/** {@code BoardingQueryApi} thật (T31, docs/adr/0065), đọc Postgres thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingQueryApiIT extends BoardingItSupport {

    @Autowired
    private BoardingQueryApi boardings;

    private void touch(long bookingId, Instant updatedAt) {
        jdbc.update("UPDATE boarding_bookings SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), bookingId);
    }

    @Test
    void countViolationsCountsLateCancelAndNoShowStrictlyAfterSince() {
        long branch = newBranch();
        long type = newKennelType(branch, "DOG", "30", 150_000);
        long staff = newStaff("RECEPTIONIST", branch);
        long customer = newCounterCustomer(null);
        Instant t = Instant.parse("2009-06-10T03:00:00.123456Z");
        long late = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "CANCELLED", staff);
        jdbc.update("UPDATE boarding_bookings SET late_cancel = true WHERE id = ?", late);
        touch(late, t);
        long noShow = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "NO_SHOW", staff);
        touch(noShow, t.plusSeconds(1));
        long early = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "CANCELLED", staff);
        touch(early, t.plusSeconds(2));

        assertThat(boardings.countViolations(customer, t.minusNanos(1_000))).isEqualTo(2);
        assertThat(boardings.countViolations(customer, t)).isEqualTo(1);
        assertThat(boardings.countViolations(customer, t.plusSeconds(1))).isZero();
    }

    @Test
    void findBookedFromReturnsBookedWithCheckOutOnOrAfter() {
        long branch = newBranch();
        long type = newKennelType(branch, "DOG", "30", 150_000);
        long staff = newStaff("RECEPTIONIST", branch);
        long customer = newCounterCustomer(null);
        long kennel = newKennel(branch, type, "C-01", "OCCUPIED");
        long a = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-12"), "BOOKED", staff);
        long b = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-15"), "BOOKED", staff);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-15"), "CHECKED_IN", kennel, staff);

        assertThat(boardings.findBookedFrom(branch, LocalDate.parse("2009-06-12"))).extracting(BookedStay::bookingId)
                .containsExactly(a, b);
        assertThat(boardings.findBookedFrom(branch, LocalDate.parse("2009-06-13"))).extracting(BookedStay::bookingId)
                .containsExactly(b);
        BookedStay first = boardings.findBookedFrom(branch, LocalDate.parse("2009-06-12")).get(0);
        assertThat(first.code()).startsWith("LTIT-");
        assertThat(first.checkInDate()).isEqualTo(LocalDate.parse("2009-06-10"));
        assertThat(first.checkOutDate()).isEqualTo(LocalDate.parse("2009-06-12"));
    }

    @Test
    void findActiveStaysByCustomerSkipsFinalStates() {
        long branch = newBranch();
        long type = newKennelType(branch, "DOG", "30", 150_000);
        long staff = newStaff("RECEPTIONIST", branch);
        long customer = newCounterCustomer(null);
        long booked = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "BOOKED", staff);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "CANCELLED", staff);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "NO_SHOW", staff);

        assertThat(boardings.findActiveStaysByCustomer(customer)).extracting(StaySummary::bookingId)
                .containsExactly(booked);
        assertThat(boardings.findActiveStaysByCustomer(customer).get(0).emergencyPhone()).isNull();
    }
}
