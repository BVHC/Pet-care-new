package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.service.BoardingCapacity;
import com.petcare.module.boarding.service.BoardingCapacity.Night;

/** Đếm chiếm chỗ theo đêm (BR-LT-01, 03; docs/adr/0065) trên Postgres thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingCapacityIT extends BoardingItSupport {

    @Autowired
    private BoardingCapacity capacity;

    private long branch;
    private long type;
    private long staff;
    private long customer;
    private long pet;

    private List<Night> nights(String from, String to) {
        return new TransactionTemplate(transactionManager).execute(s ->
                capacity.nights(branch, type, LocalDate.parse(from), LocalDate.parse(to), TODAY));
    }

    private void setUpData(String... kennelStatuses) {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        staff = newStaff("RECEPTIONIST", branch);
        customer = newCounterCustomer(null);
        pet = newPet(customer, "DOG");
        for (int i = 0; i < kennelStatuses.length; i++) {
            newKennel(branch, type, "C-0" + i, kennelStatuses[i]);
        }
    }

    @Test
    void capacityExcludesMaintenance() {
        setUpData("AVAILABLE", "OCCUPIED", "MAINTENANCE");

        assertThat(nights("2009-06-12", "2009-06-14")).extracting(Night::capacity).containsExactly(2, 2);
    }

    @Test
    void bookedAndCheckedInOccupyCheckInUntilBeforeCheckOut() {
        setUpData("AVAILABLE", "AVAILABLE");
        newBooking(customer, pet, branch, type, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-14"),
                "BOOKED", staff);

        assertThat(nights("2009-06-11", "2009-06-15")).extracting(Night::occupied).containsExactly(0, 1, 1, 0);
    }

    @Test
    void overdueOccupiesTodayPlusHoldNights() {
        setUpData("AVAILABLE");
        long kennel = newKennel(branch, type, "C-OD", "OCCUPIED");
        newBooking(customer, pet, branch, type, LocalDate.parse("2009-06-01"), LocalDate.parse("2009-06-05"),
                "OVERDUE", kennel, staff);

        // today 10/06, boarding.overdue_hold_nights = 2 → 10, 11, 12
        assertThat(nights("2009-06-09", "2009-06-15")).extracting(Night::occupied)
                .containsExactly(0, 1, 1, 1, 0, 0);
    }

    @Test
    void cancelledAndNoShowDoNotOccupy() {
        setUpData("AVAILABLE");
        long other = newPet(customer, "DOG");
        newBooking(customer, pet, branch, type, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-14"),
                "CANCELLED", staff);
        newBooking(customer, other, branch, type, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-14"),
                "NO_SHOW", staff);

        assertThat(nights("2009-06-12", "2009-06-14")).extracting(Night::occupied).containsExactly(0, 0);
    }

    @Test
    void otherBranchAndOtherTypeAreIgnored() {
        setUpData("AVAILABLE");
        long otherBranch = newBranch();
        long otherType = newKennelType(branch, "DOG", "30", 100_000);
        long otherPet = newPet(customer, "DOG");
        newBooking(customer, pet, otherBranch, type, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-14"),
                "BOOKED", staff);
        newBooking(customer, otherPet, branch, otherType, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), "BOOKED", staff);

        assertThat(nights("2009-06-12", "2009-06-14")).extracting(Night::occupied).containsExactly(0, 0);
    }

    @Test
    void shortNightsListsNightsWhereOccupiedReachesCapacity() {
        setUpData("AVAILABLE");
        newBooking(customer, pet, branch, type, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-13"),
                "BOOKED", staff);

        List<LocalDate> shortNights = new TransactionTemplate(transactionManager).execute(s ->
                capacity.shortNights(branch, type, LocalDate.parse("2009-06-11"), LocalDate.parse("2009-06-14"),
                        TODAY));

        assertThat(shortNights).containsExactly(LocalDate.parse("2009-06-12"));
    }
}
