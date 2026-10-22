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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Còn chỗ theo đêm (boarding-v1 #7, UC58; docs/adr/0065). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingAvailabilityIT extends BoardingItSupport {

    private long branch;
    private long type;
    private long staffId;
    private String customerToken;
    private String receptionist;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, type, "C-01", "AVAILABLE");
        newKennel(branch, type, "C-02", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        customerToken = token(newOnlineCustomer()[0]);
    }

    private ResponseEntity<Map<String, Object>> get(String token, long branchId, long kennelType, String in,
            String out) {
        return call(HttpMethod.GET, "/api/branches/" + branchId + "/boarding-availability?kennelTypeId=" + kennelType
                + "&checkInDate=" + in + "&checkOutDate=" + out, token, null);
    }

    @Test
    void customerSeesNightlyRemainingAndCurrentPrice() {
        long customer = newCounterCustomer(null);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "BOOKED", staffId);

        Map<String, Object> data = data(get(customerToken, branch, type, "2009-06-12", "2009-06-14"), HttpStatus.OK);

        assertThat(number(data.get("nightlyPrice"))).isEqualTo(150_000L);
        assertThat(data).containsEntry("available", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nights = (List<Map<String, Object>>) data.get("nights");
        assertThat(nights).extracting(n -> n.get("date")).containsExactly("2009-06-12", "2009-06-13");
        assertThat(nights).extracting(n -> number(n.get("remaining"))).containsExactly(1L, 2L);
        assertThat(nights).extracting(n -> number(n.get("capacity"))).containsExactly(2L, 2L);
    }

    @Test
    void fullNightMakesRangeUnavailable() {
        long customer = newCounterCustomer(null);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "BOOKED", staffId);
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "BOOKED", staffId);

        Map<String, Object> data = data(get(receptionist, branch, type, "2009-06-12", "2009-06-14"), HttpStatus.OK);

        assertThat(data).containsEntry("available", false);
    }

    @Test
    void nightCountOutsideConfiguredRangeIsBrLt02() {
        assertRule(get(customerToken, branch, type, "2009-06-12", "2009-07-13"), "BR-LT-02");
        assertRule(get(customerToken, branch, type, "2009-06-12", "2009-06-12"), "BR-LT-02");
        assertRule(get(customerToken, branch, type, "2009-06-14", "2009-06-12"), "BR-LT-02");
    }

    @Test
    void disabledKennelTypeIsBrLt02() {
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                type);

        assertRule(get(customerToken, branch, type, "2009-06-12", "2009-06-14"), "BR-LT-02");
    }

    @Test
    void draftBranchIsNotFoundForCustomerButVisibleToItsReceptionist() {
        long draft = newBranch("DRAFT");
        long draftType = newKennelType(draft, "DOG", "30", 150_000);
        String draftReceptionist = token(newStaff("RECEPTIONIST", draft));

        assertThat(get(customerToken, draft, draftType, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(draftReceptionist, draft, draftType, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void receptionistOfAnotherBranchIsForbiddenAndAnonymousIsUnauthorized() {
        long otherBranch = newBranch();
        String other = token(newStaff("RECEPTIONIST", otherBranch));

        assertThat(get(other, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(null, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
