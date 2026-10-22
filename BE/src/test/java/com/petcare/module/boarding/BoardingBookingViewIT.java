package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Xem đặt chỗ (boarding-v1 #9, #10; docs/adr/0065). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingBookingViewIT extends BoardingItSupport {

    private long branch;
    private long type;
    private long staffId;
    private String receptionist;
    private long customerId;
    private String customer;
    private long pet;
    private long first;
    private long second;
    private long third;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        long kennel = newKennel(branch, type, "C-01", "OCCUPIED");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        long[] online = newOnlineCustomer();
        customerId = online[1];
        customer = token(online[0]);
        pet = newPet(customerId, "DOG");
        long pet2 = newPet(customerId, "DOG");
        long pet3 = newPet(customerId, "DOG");
        first = newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-20"), LocalDate.parse("2009-06-22"),
                "BOOKED", staffId);
        second = newBooking(customerId, pet2, branch, type, LocalDate.parse("2009-06-25"),
                LocalDate.parse("2009-06-27"), "CANCELLED", staffId);
        third = newBooking(customerId, pet3, branch, type, LocalDate.parse("2009-06-08"),
                LocalDate.parse("2009-06-12"), "CHECKED_IN", kennel, staffId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageContent(String token, String query) {
        Map<String, Object> page = data(call(HttpMethod.GET, "/api/boarding-bookings" + query, token, null),
                HttpStatus.OK);
        return (List<Map<String, Object>>) page.get("content");
    }

    @Test
    void customerSeesOnlyOwnBookingsAndStaffOnlyOwnBranch() {
        long other = newCounterCustomer(null);
        long otherPet = newPet(other, "DOG");
        long otherBooking = newBooking(other, otherPet, branch, type, LocalDate.parse("2009-07-01"),
                LocalDate.parse("2009-07-02"), "BOOKED", staffId);
        long otherBranch = newBranch();
        long otherType = newKennelType(otherBranch, "DOG", "30", 100_000);
        long foreign = newBooking(other, newPet(other, "DOG"), otherBranch, otherType, LocalDate.parse("2009-07-01"),
                LocalDate.parse("2009-07-02"), "BOOKED", staffId);

        assertThat(pageContent(customer, "")).extracting(b -> number(b.get("bookingId")))
                .containsExactlyInAnyOrder(first, second, third);
        assertThat(pageContent(receptionist, "")).extracting(b -> number(b.get("bookingId")))
                .contains(first, second, third, otherBooking).doesNotContain(foreign);
    }

    @Test
    void sortedByCheckInDescThenIdDescAndFiltersCombine() {
        assertThat(pageContent(customer, "")).extracting(b -> number(b.get("bookingId")))
                .containsExactly(second, first, third);
        assertThat(pageContent(receptionist, "?status=BOOKED&petId=" + pet)).extracting(b -> number(b.get("bookingId")))
                .containsExactly(first);
        assertThat(pageContent(receptionist, "?from=2009-06-21&to=2009-06-26"))
                .extracting(b -> number(b.get("bookingId"))).containsExactly(second, first);
        assertThat(pageContent(receptionist, "?inStay=true")).extracting(b -> number(b.get("bookingId")))
                .containsExactly(third);
    }

    @Test
    void missingCareLogTodayFlagsAndFiltersInStayPetsWithoutALog() {
        List<Map<String, Object>> content = pageContent(receptionist, "?missingCareLogToday=true");
        assertThat(content).extracting(b -> number(b.get("bookingId"))).containsExactly(third);
        assertThat(content.get(0)).containsEntry("missingCareLogToday", true);

        jdbc.update("INSERT INTO care_logs (boarding_booking_id, log_date, recorded_by) VALUES (?, DATE '2009-06-10', ?)",
                third, staffId);

        assertThat(pageContent(receptionist, "?missingCareLogToday=true")).isEmpty();
    }

    @Test
    void pagingReportsTotals() {
        Map<String, Object> page = data(call(HttpMethod.GET, "/api/boarding-bookings?size=2&page=1", customer, null),
                HttpStatus.OK);

        assertThat(number(page.get("totalElements"))).isEqualTo(3L);
        assertThat(number(page.get("totalPages"))).isEqualTo(2L);
        assertThat((List<?>) page.get("content")).hasSize(1);
    }

    @Test
    void fromAfterToIs400() {
        assertError(call(HttpMethod.GET, "/api/boarding-bookings?from=2009-07-01&to=2009-06-01", receptionist, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void detailEnforcesOwnerAndBranchScope() {
        long other = newCounterCustomer(null);
        long otherBooking = newBooking(other, newPet(other, "DOG"), branch, type, LocalDate.parse("2009-07-01"),
                LocalDate.parse("2009-07-02"), "BOOKED", staffId);
        String otherCustomer = token(newOnlineCustomer()[0]);
        String foreignStaff = token(newStaff("RECEPTIONIST", newBranch()));
        String vet = token(newStaff("VET", branch));

        Map<String, Object> detail = data(call(HttpMethod.GET, "/api/boarding-bookings/" + first, customer, null),
                HttpStatus.OK);
        assertThat(detail).containsEntry("status", "BOOKED").containsEntry("missingCareLogToday", false);
        assertThat(detail.get("customerName")).isNotNull();
        assertThat(detail.get("petName")).isNotNull();
        assertThat(call(HttpMethod.GET, "/api/boarding-bookings/" + otherBooking, customer, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/boarding-bookings/" + first, otherCustomer, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/boarding-bookings/" + first, foreignStaff, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/boarding-bookings/" + first, vet, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/boarding-bookings/999999999", receptionist, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
