package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.service.BoardingClinicCause;
import com.petcare.module.boarding.service.BoardingNoShowService;
import com.petcare.module.boarding.service.BoardingNoShowService.NoShowResult;

/** Đặt chỗ#5 — phòng khám hủy theo ba sự kiện (docs/adr/0065), qua HTTP của bên phát sự kiện. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingClinicCancellationIT extends BoardingItSupport {

    @Autowired
    private BoardingNoShowService noShows;
    @Autowired
    private BoardingQueryApi boardings;

    private long branch;
    private long type;
    private long staffId;
    private String receptionist;
    private String manager;
    private long onlineAccount;
    private String onlineEmail;
    private long onlineCustomer;
    private String online;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, type, "C-01", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        manager = token(newStaff("BRANCH_MANAGER", branch));
        long[] account = newOnlineCustomer();
        onlineAccount = account[0];
        onlineCustomer = account[1];
        online = token(onlineAccount);
        onlineEmail = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, onlineAccount);
    }

    private long booked(long customer, long pet, String in, String out) {
        return newBooking(customer, pet, branch, type, LocalDate.parse(in), LocalDate.parse(out), "BOOKED", staffId);
    }

    private ResponseEntity<Map<String, Object>> markDeceased(String token, long pet) {
        return call(HttpMethod.POST, "/api/pets/" + pet + "/mark-deceased", token,
                Map.of("deceasedOn", "2009-06-10", "confirmed", true));
    }

    private List<Map<String, Object>> outboxOf(long bookingId) {
        String code = jdbc.queryForObject("SELECT code FROM boarding_bookings WHERE id = ?", String.class, bookingId);
        return jdbc.queryForList("""
                SELECT channel, template_code, recipient_account_id, recipient_email, CAST(payload AS text) AS payload
                FROM notification_outbox WHERE payload ->> 'ma_dat_cho' = ? ORDER BY channel""", code);
    }

    private void assertCancelledByClinic(long id, BoardingClinicCause cause) {
        assertThat(jdbc.queryForMap("""
                SELECT status, cancel_source, late_cancel, cancel_reason FROM boarding_bookings WHERE id = ?""", id))
                .containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC")
                .containsEntry("late_cancel", false).containsEntry("cancel_reason", cause.reason());
    }

    @Test
    void petDeceasedCancelsBookedAsClinicAndNotifiesAccountByInAppAndEmail() {
        long pet = newPet(onlineCustomer, "DOG");
        long other = newPet(onlineCustomer, "DOG");
        long mine = booked(onlineCustomer, pet, "2009-06-12", "2009-06-14");
        long done = newBooking(onlineCustomer, pet, branch, type, LocalDate.parse("2009-06-01"),
                LocalDate.parse("2009-06-02"), "NO_SHOW", staffId);
        long unaffected = booked(onlineCustomer, other, "2009-06-12", "2009-06-14");

        assertThat(markDeceased(online, pet).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertCancelledByClinic(mine, BoardingClinicCause.PET_DECEASED);
        assertThat(statusOf(done)).isEqualTo("NO_SHOW");
        assertThat(statusOf(unaffected)).isEqualTo("BOOKED");
        List<Map<String, Object>> outbox = outboxOf(mine);
        assertThat(outbox).extracting(r -> r.get("channel")).containsExactlyInAnyOrder("EMAIL", "IN_APP");
        assertThat(outbox).filteredOn(r -> "EMAIL".equals(r.get("channel")))
                .extracting(r -> r.get("recipient_email")).containsExactly(onlineEmail);
        assertThat(outbox).extracting(r -> r.get("template_code")).containsExactlyInAnyOrder(
                "BOARDING_CANCELLED_BY_CLINIC", "BOARDING_CANCELLED_BY_CLINIC_APP");
        assertThat(outbox.get(0).get("payload").toString()).contains("12/06/2009").contains("14/06/2009")
                .contains(BoardingClinicCause.PET_DECEASED.reason());
        assertThat(outboxOf(unaffected)).isEmpty();
    }

    @Test
    void ownerTransferCancelsBookedAndNotifiesThePreviousOwner() {
        long pet = newPet(onlineCustomer, "DOG");
        long mine = booked(onlineCustomer, pet, "2009-06-12", "2009-06-14");
        long newOwner = newOnlineCustomer()[1];

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + pet + "/transfer-owner",
                receptionist, Map.of("newCustomerId", newOwner, "previousOwnerConfirmed", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCancelledByClinic(mine, BoardingClinicCause.PET_OWNER_TRANSFERRED);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM boarding_bookings WHERE id = ?", Long.class, mine))
                .isEqualTo(onlineCustomer);
        assertThat(outboxOf(mine)).extracting(r -> r.get("recipient_email"))
                .containsExactlyInAnyOrder(onlineEmail, null);
    }

    @Test
    void holidayOnCheckInOrCheckOutDayCancelsAffectedBookingsWhenManagerChoosesTo() {
        long hit = booked(onlineCustomer, newPet(onlineCustomer, "DOG"), "2009-06-20", "2009-06-22");
        long hitOut = booked(onlineCustomer, newPet(onlineCustomer, "DOG"), "2009-06-18", "2009-06-20");
        long miss = booked(onlineCustomer, newPet(onlineCustomer, "DOG"), "2009-06-21", "2009-06-23");
        Map<String, Object> body = new HashMap<>();
        body.put("holidayDate", "2009-06-20");
        body.put("reason", "IT nghỉ");
        body.put("cancelAffected", true);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/branches/" + branch + "/holidays",
                manager, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s", response.getBody()).isTrue();
        assertCancelledByClinic(hit, BoardingClinicCause.HOLIDAY_ADDED);
        assertCancelledByClinic(hitOut, BoardingClinicCause.HOLIDAY_ADDED);
        assertThat(statusOf(miss)).isEqualTo("BOOKED");
        assertThat(outboxOf(hit)).hasSize(2);
    }

    @Test
    void noShowNeverTouchesAClinicCancelledBookingAndNoViolationIsCounted() {
        long pet = newPet(onlineCustomer, "DOG");
        long mine = booked(onlineCustomer, pet, "2009-06-12", "2009-06-14");
        assertThat(markDeceased(online, pet).getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.set(Instant.parse("2009-06-13T12:00:00Z"));

        NoShowResult result = new TransactionTemplate(transactionManager).execute(s -> noShows.markNoShow(mine));

        assertThat(result).isEqualTo(NoShowResult.SKIPPED);
        assertThat(statusOf(mine)).isEqualTo("CANCELLED");
        assertThat(boardings.countViolations(onlineCustomer, Instant.EPOCH)).isZero();
    }

    @Test
    void listenerFailureRollsBackThePublisher() {
        long pet = newPet(onlineCustomer, "DOG");
        long mine = booked(onlineCustomer, pet, "2009-06-12", "2009-06-14");
        String name = "boarding_it_reject_" + SEQ.incrementAndGet();
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'chặn để thử rollback'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE UPDATE ON boarding_bookings FOR EACH ROW EXECUTE FUNCTION "
                + name + "()");
        try {
            ResponseEntity<Map<String, Object>> response = markDeceased(online, pet);

            assertThat(response.getStatusCode().is5xxServerError()).isTrue();
            assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", Object.class, pet)).isNull();
            assertThat(statusOf(mine)).isEqualTo("BOOKED");
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON boarding_bookings");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    void counterProfileGetsEmailOnlyAndNoEmailGetsNothing() {
        String counterEmail = "boarding-it-" + UUID.randomUUID() + "@petcare.test";
        long withEmail = newCounterCustomer(counterEmail);
        long withoutEmail = newCounterCustomer(null);
        long petA = newPet(withEmail, "DOG");
        long petB = newPet(withoutEmail, "DOG");
        long a = booked(withEmail, petA, "2009-06-12", "2009-06-14");
        long b = booked(withoutEmail, petB, "2009-06-12", "2009-06-14");

        assertThat(markDeceased(receptionist, petA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(markDeceased(receptionist, petB).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertCancelledByClinic(a, BoardingClinicCause.PET_DECEASED);
        assertCancelledByClinic(b, BoardingClinicCause.PET_DECEASED);
        assertThat(outboxOf(a)).extracting(r -> r.get("channel"), r -> r.get("recipient_email"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EMAIL", counterEmail));
        assertThat(outboxOf(b)).isEmpty();
    }
}
