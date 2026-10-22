package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;

/** Gia hạn và hủy đặt chỗ (boarding-v1 #11, #12; Đặt chỗ#2, #4; docs/adr/0065). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingChangeIT extends BoardingItSupport {

    @Autowired
    private SystemConfigService configs;

    private long branch;
    private long type;
    private long staffId;
    private String receptionist;
    private long customerId;
    private String customer;
    private long customerAccount;
    private long pet;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, type, "C-01", "AVAILABLE");
        newKennel(branch, type, "C-02", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        long[] online = newOnlineCustomer();
        customerId = online[1];
        customerAccount = online[0];
        customer = token(online[0]);
        pet = newPet(customerId, "DOG");
    }

    private long booked(String in, String out) {
        return newBooking(customerId, pet, branch, type, LocalDate.parse(in), LocalDate.parse(out), "BOOKED", staffId);
    }

    private ResponseEntity<Map<String, Object>> extend(String token, long id, String out) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/extend", token, Map.of("checkOutDate", out));
    }

    private ResponseEntity<Map<String, Object>> cancel(String token, long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/cancel", token, null);
    }

    // ---------------------------------------------------------------- gia hạn

    @Test
    void extendAddsNightsWhenCapacityAllows() {
        long id = booked("2009-06-12", "2009-06-14");

        Map<String, Object> data = data(extend(customer, id, "2009-06-16"), HttpStatus.OK);

        assertThat(data).containsEntry("checkOutDate", "2009-06-16").containsEntry("status", "BOOKED");
        assertThat(jdbc.queryForObject("SELECT check_out_date FROM boarding_bookings WHERE id = ?", LocalDate.class,
                id)).isEqualTo(LocalDate.parse("2009-06-16"));
    }

    @Test
    void extendIntoAFullNightIsBrLt03ListingTheNight() {
        long id = booked("2009-06-12", "2009-06-14");
        long counter = newCounterCustomer(null);
        newBooking(counter, newPet(counter, "DOG"), branch, type, LocalDate.parse("2009-06-14"),
                LocalDate.parse("2009-06-15"), "BOOKED", staffId);
        newBooking(counter, newPet(counter, "DOG"), branch, type, LocalDate.parse("2009-06-14"),
                LocalDate.parse("2009-06-15"), "BOOKED", staffId);

        ResponseEntity<Map<String, Object>> response = extend(receptionist, id, "2009-06-16");

        assertRule(response, "BR-LT-03");
        assertThat((String) response.getBody().get("message")).contains("14/06");
    }

    @Test
    void extendRuleFailures() {
        long id = booked("2009-06-12", "2009-06-14");
        assertRule(extend(customer, id, "2009-07-20"), "BR-LT-02");
        assertError(extend(customer, id, "2009-06-14"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(extend(customer, id, "2009-06-13"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        long overlapPet = newPet(customerId, "DOG");
        newBooking(customerId, overlapPet, branch, type, LocalDate.parse("2009-06-20"), LocalDate.parse("2009-06-22"),
                "BOOKED", staffId);
        long petBookingElsewhere = newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-15"),
                LocalDate.parse("2009-06-17"), "BOOKED", staffId);
        assertRule(extend(customer, id, "2009-06-16"), "BR-LT-02");
        assertThat(statusOf(petBookingElsewhere)).isEqualTo("BOOKED");
    }

    @Test
    void extendOnOrAfterCheckOutDayIsBrLt06() {
        long id = booked("2009-06-08", "2009-06-10");

        assertRule(extend(customer, id, "2009-06-12"), "BR-LT-06");
    }

    @Test
    void extendCancelledOrOverdueIs409() {
        long cancelled = newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), "CANCELLED", staffId);
        assertError(extend(customer, cancelled, "2009-06-16"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        long kennel = newKennel(branch, type, "C-OD", "OCCUPIED");
        long other = newPet(customerId, "DOG");
        long overdue = newBooking(customerId, other, branch, type, LocalDate.parse("2009-06-01"),
                LocalDate.parse("2009-06-05"), "OVERDUE", kennel, staffId);
        assertError(extend(customer, overdue, "2009-06-16"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void maxNightsChangeAppliesOnlyToLaterActions() {
        long id = booked("2009-06-12", "2009-06-20");
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'boarding.max_nights'",
                String.class);
        try {
            jdbc.update("UPDATE system_configs SET value = '8' WHERE key = 'boarding.max_nights'");
            configs.reload();

            assertThat(statusOf(id)).isEqualTo("BOOKED");
            assertRule(extend(customer, id, "2009-06-21"), "BR-LT-02");
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'boarding.max_nights'", original);
            configs.reload();
        }
    }

    // ---------------------------------------------------------------- hủy

    @Test
    void lateCancelBoundaryIsMidnightMinus24Hours() {
        // ngày nhận 12/06: mốc = 00:00 giờ VN 12/06 (11/06 17:00Z) trừ 24 giờ = 10/06 17:00Z
        long early = booked("2009-06-12", "2009-06-14");
        long late = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), "BOOKED", staffId);
        clock.set(Instant.parse("2009-06-10T16:59:59.999999Z"));
        data(cancel(token(customerAccount), early), HttpStatus.OK);
        assertThat(jdbc.queryForMap("SELECT status, late_cancel, cancel_source FROM boarding_bookings WHERE id = ?",
                early)).containsEntry("status", "CANCELLED").containsEntry("late_cancel", false)
                .containsEntry("cancel_source", "CUSTOMER");

        clock.set(Instant.parse("2009-06-10T17:00:00Z"));
        Map<String, Object> data = data(cancel(token(staffId), late), HttpStatus.OK);
        assertThat(data).containsEntry("lateCancel", true).containsEntry("cancelSource", "STAFF");
    }

    @Test
    void cancelOnCheckInDayIsBrLt06AndTwiceIs409() {
        long today = booked("2009-06-10", "2009-06-12");
        assertRule(cancel(customer, today), "BR-LT-06");

        long id = booked("2009-06-20", "2009-06-22");
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/cancel", customer,
                Map.of("reason", "Đổi lịch")), HttpStatus.OK);
        assertError(cancel(customer, id), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM boarding_bookings WHERE id = ?", String.class, id))
                .isEqualTo("Đổi lịch");
    }

    @Test
    void lateCancelReachingTheThresholdCreatesRestrictionAtTheStoredUpdatedAt() {
        for (int i = 0; i < 2; i++) {
            long noShow = newBooking(customerId, newPet(customerId, "DOG"), branch, type,
                    LocalDate.parse("2009-06-01"), LocalDate.parse("2009-06-02"), "NO_SHOW", staffId);
            jdbc.update("UPDATE boarding_bookings SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2009-06-05T00:00:00Z")), noShow);
        }
        long id = booked("2009-06-11", "2009-06-14");

        data(cancel(customer, id), HttpStatus.OK);

        Map<String, Object> restriction = jdbc.queryForMap(
                "SELECT starts_at, violation_count FROM booking_restrictions WHERE customer_id = ?", customerId);
        assertThat(((Number) restriction.get("violation_count")).intValue()).isEqualTo(3);
        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM boarding_bookings WHERE id = ?",
                Timestamp.class, id).toInstant();
        assertThat(((Timestamp) restriction.get("starts_at")).toInstant()).isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM boarding_bookings WHERE customer_id = ? AND status IN ('NO_SHOW', 'CANCELLED') "
                        + "AND updated_at > ?", Long.class, customerId, Timestamp.from(updatedAt)))
                .as("vi phạm đã gây ra hạn chế không bị đếm lại").isZero();
    }

    @Test
    void lateCancelRollsBackWhenCreatingTheRestrictionFails() {
        for (int i = 0; i < 2; i++) {
            long noShow = newBooking(customerId, newPet(customerId, "DOG"), branch, type,
                    LocalDate.parse("2009-06-01"), LocalDate.parse("2009-06-02"), "NO_SHOW", staffId);
            jdbc.update("UPDATE boarding_bookings SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2009-06-05T00:00:00Z")), noShow);
        }
        long id = booked("2009-06-11", "2009-06-14");
        String name = "boarding_it_reject_" + SEQ.incrementAndGet();
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'chặn để thử rollback'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON booking_restrictions FOR EACH ROW EXECUTE "
                + "FUNCTION " + name + "()");
        try {
            ResponseEntity<Map<String, Object>> response = cancel(customer, id);

            assertThat(response.getStatusCode().is5xxServerError() || response.getStatusCode().is4xxClientError())
                    .isTrue();
            assertThat(jdbc.queryForMap("SELECT status, late_cancel FROM boarding_bookings WHERE id = ?", id))
                    .containsEntry("status", "BOOKED").containsEntry("late_cancel", false);
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON booking_restrictions");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    void ownershipAndScopeAreEnforced() {
        long id = booked("2009-06-20", "2009-06-22");
        String other = token(newOnlineCustomer()[0]);
        String foreign = token(newStaff("RECEPTIONIST", newBranch()));

        assertThat(cancel(other, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(extend(other, id, "2009-06-25").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel(foreign, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel(customer, 999_999_999L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(id)).isEqualTo("BOOKED");
    }
}
