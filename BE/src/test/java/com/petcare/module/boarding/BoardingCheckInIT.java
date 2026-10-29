package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.api.VaccinationQueryApi;

/**
 * Nhận thú và hủy vì hết chuồng (boarding-v1 #13–15; Đặt chỗ#3, #5; Chuồng#2; docs/adr/0085). Era 2009,
 * {@code START} = 2009-06-10 10:00 giờ Việt Nam, chi nhánh mở 08:00–19:00. Mũi tiêm bắt buộc dùng stand-in
 * {@code @Primary} (như {@code BoardingBookingIT}): hàm thật của BE-1 đã có test riêng, ở đây chỉ cần kết quả "thiếu mũi".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class, BoardingCheckInIT.TestBeans.class})
class BoardingCheckInIT extends BoardingItSupport {

    static final AtomicReference<Map<Long, List<VaccinationQueryApi.BoardingVaccineGap>>> GAPS =
            new AtomicReference<>(Map.of());

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        VaccinationQueryApi vaccinationStandIn() {
            return new VaccinationQueryApi() {
                @Override
                public List<BoardingVaccineGap> findBoardingVaccineGaps(Long petId, LocalDate onDate) {
                    return GAPS.get().getOrDefault(petId, List.of());
                }

                @Override
                public boolean existsByVaccineType(Long vaccineTypeId) {
                    return false;
                }

                @Override
                public Map<Long, Long> findVaccineTypeIds(java.util.Collection<Long> vaccinationIds) {
                    return Map.of();
                }
            };
        }
    }

    private long branch;
    private long type;
    private long kennel;
    private long staffId;
    private String receptionist;
    private String caretaker;
    private long customerId;
    private long customerAccount;
    private long pet;

    @BeforeEach
    void setUp() {
        GAPS.set(Map.of());
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        kennel = newKennel(branch, type, "C-01", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        caretaker = token(newStaff("CARETAKER", branch));
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        customerId = online[1];
        pet = newPet(customerId, "DOG");
    }

    private long booked(String in, String out) {
        return newBooking(customerId, pet, branch, type, LocalDate.parse(in), LocalDate.parse(out), "BOOKED", staffId);
    }

    private Map<String, Object> body(Long kennelId, Object weight) {
        Map<String, Object> body = new HashMap<>();
        body.put("kennelId", kennelId);
        body.put("weightKg", weight);
        body.put("healthCondition", "Tỉnh táo");
        body.put("belongings", "Chăn");
        body.put("dietInstructions", "Hạt ướt");
        body.put("emergencyPhone", "0901234567");
        return body;
    }

    private ResponseEntity<Map<String, Object>> checkIn(String token, long id, Map<String, Object> body) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/check-in", token, body);
    }

    private ResponseEntity<Map<String, Object>> readiness(String token, long id) {
        return call(HttpMethod.GET, "/api/boarding-bookings/" + id + "/check-in-readiness", token, null);
    }

    private ResponseEntity<Map<String, Object>> cancelNoKennel(String token, long id) {
        return call(HttpMethod.POST, "/api/boarding-bookings/" + id + "/cancel-no-kennel", token, null);
    }

    private String kennelStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, id);
    }

    private int count(String table, long bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE boarding_booking_id = ?",
                Integer.class, bookingId);
    }

    private void assertUntouched(long bookingId) {
        assertThat(statusOf(bookingId)).isEqualTo("BOOKED");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(count("boarding_check_ins", bookingId)).isZero();
        assertThat(count("weight_records", bookingId)).isZero();
    }

    // ---------------------------------------------------------------- nhận thú

    @Test
    void checkInOnTheDayAssignsKennelAndRecordsIntakeAndWeight() {
        long id = booked("2009-06-10", "2009-06-12");

        Map<String, Object> data = data(checkIn(receptionist, id, body(kennel, 4.3)), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CHECKED_IN").containsEntry("missingCareLogToday", true)
                .containsEntry("checkInDate", "2009-06-10");
        assertThat(number(data.get("kennelId"))).isEqualTo(kennel);
        assertThat(data.get("actualCheckInAt")).isNotNull();
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        Map<String, Object> weight = jdbc.queryForMap("""
                SELECT source, weight_kg, recorded_by FROM weight_records WHERE boarding_booking_id = ?""", id);
        assertThat(weight.get("source")).isEqualTo("INTAKE");
        assertThat(weight.get("weight_kg").toString()).isEqualTo("4.30");
        assertThat(((Number) weight.get("recorded_by")).longValue()).isEqualTo(
                jdbc.queryForObject("SELECT received_by FROM boarding_check_ins WHERE boarding_booking_id = ?",
                        Long.class, id));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM boarding_check_ins WHERE boarding_booking_id = ?", id);
        assertThat(row).containsEntry("health_condition", "Tỉnh táo").containsEntry("belongings", "Chăn")
                .containsEntry("diet_instructions", "Hạt ướt").containsEntry("emergency_phone", "0901234567");
    }

    @Test
    void caretakerCanCheckInToo() {
        long id = booked("2009-06-10", "2009-06-12");

        data(checkIn(caretaker, id, body(kennel, 4)), HttpStatus.OK);

        assertThat(statusOf(id)).isEqualTo("CHECKED_IN");
    }

    @Test
    void missingRequiredVaccineIsBrLt05AndWritesNothing() {
        long id = booked("2009-06-10", "2009-06-12");
        long vaccineType = jdbc.queryForObject("""
                INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id""", Long.class,
                "IT Dại " + java.util.UUID.randomUUID());
        GAPS.set(Map.of(pet, List.of(new VaccinationQueryApi.BoardingVaccineGap(vaccineType, null, null))));
        try {
            ResponseEntity<Map<String, Object>> response = checkIn(receptionist, id, body(kennel, 4));

            assertRule(response, "BR-LT-05");
            assertUntouched(id);
        } finally {
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", vaccineType);
        }
    }

    @Test
    void kennelAndWeightRulesAreBrLt08() {
        long id = booked("2009-06-10", "2009-06-12");
        long otherType = newKennelType(branch, "DOG", "30", 120_000);
        long wrongType = newKennel(branch, otherType, "D-01", "AVAILABLE");
        long maintenance = newKennel(branch, type, "C-02", "MAINTENANCE");
        long otherBranch = newBranch();
        long otherBranchKennel = newKennel(otherBranch, newKennelType(otherBranch, "DOG", "30", 150_000), "X-01",
                "AVAILABLE");

        assertRule(checkIn(receptionist, id, body(wrongType, 4)), "BR-LT-08");
        assertRule(checkIn(receptionist, id, body(maintenance, 4)), "BR-LT-08");
        assertRule(checkIn(receptionist, id, body(otherBranchKennel, 4)), "BR-LT-08");
        assertRule(checkIn(receptionist, id, body(null, 4)), "BR-LT-08");
        assertRule(checkIn(receptionist, id, body(kennel, null)), "BR-LT-08");
        assertError(checkIn(receptionist, id, body(999_999_999L, 4)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertUntouched(id);
    }

    @Test
    void weightOutOfRangeIsRejectedAndWritesNothing() {
        long id = booked("2009-06-10", "2009-06-12");

        assertRule(checkIn(receptionist, id, body(kennel, 0)), "BR-KH-04");
        assertRule(checkIn(receptionist, id, body(kennel, 10000)), "BR-KH-04");
        assertRule(checkIn(receptionist, id, body(kennel, 0.001)), "BR-KH-04");
        assertUntouched(id);
    }

    @Test
    void invalidBodyIsValidationFailed() {
        long id = booked("2009-06-10", "2009-06-12");
        Map<String, Object> badPhone = body(kennel, 4);
        badPhone.put("emergencyPhone", "+84901234567");
        Map<String, Object> blankHealth = body(kennel, 4);
        blankHealth.put("healthCondition", "  ");

        assertError(checkIn(receptionist, id, badPhone), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(checkIn(receptionist, id, blankHealth), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertUntouched(id);
    }

    @Test
    void outsideOpeningHoursIsBrLt04() {
        long id = booked("2009-06-10", "2009-06-12");
        clock.set(Instant.parse("2009-06-10T13:00:00Z"));    // 20:00 giờ Việt Nam

        assertRule(checkIn(receptionist, id, body(kennel, 4)), "BR-LT-04");
        assertUntouched(id);
    }

    @Test
    void afterTheCheckInDateIsBrLt06() {
        long id = booked("2009-06-08", "2009-06-12");

        assertRule(checkIn(receptionist, id, body(kennel, 4)), "BR-LT-06");
        assertUntouched(id);
    }

    @Test
    void earlyCheckInMovesTheCheckInDateToToday() {
        long id = booked("2009-06-12", "2009-06-14");

        Map<String, Object> data = data(checkIn(receptionist, id, body(kennel, 4)), HttpStatus.OK);

        assertThat(data).containsEntry("checkInDate", "2009-06-10").containsEntry("checkOutDate", "2009-06-14");
        assertThat(jdbc.queryForObject("SELECT check_in_date FROM boarding_bookings WHERE id = ?", LocalDate.class,
                id)).isEqualTo(LocalDate.parse("2009-06-10"));
    }

    @Test
    void earlyCheckInIntoAFullNightIsBrLt03() {
        long id = booked("2009-06-12", "2009-06-14");
        long counter = newCounterCustomer(null);
        newBooking(counter, newPet(counter, "DOG"), branch, type, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-11"), "BOOKED", staffId);

        ResponseEntity<Map<String, Object>> response = checkIn(receptionist, id, body(kennel, 4));

        assertRule(response, "BR-LT-03");
        assertThat((String) response.getBody().get("message")).contains("10/06");
        assertUntouched(id);
    }

    @Test
    void earlyCheckInBeyondMaxNightsIsBrLt02() {
        long id = booked("2009-06-25", "2009-07-12");        // 32 đêm tính từ 10/06

        assertRule(checkIn(receptionist, id, body(kennel, 4)), "BR-LT-02");
        assertUntouched(id);
    }

    @Test
    void earlyCheckInOverlappingAnotherBookingOfTheSamePetIsBrLt02() {
        long id = booked("2009-06-12", "2009-06-14");
        newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-10"), LocalDate.parse("2009-06-12"),
                "BOOKED", staffId);

        assertRule(checkIn(receptionist, id, body(kennel, 4)), "BR-LT-02");
        assertUntouched(id);
    }

    @Test
    void checkInAuthorizationAndState() {
        long id = booked("2009-06-10", "2009-06-12");
        long otherBranch = newBranch();
        String foreign = token(newStaff("RECEPTIONIST", otherBranch));

        assertError(checkIn(token(customerAccount), id, body(kennel, 4)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(checkIn(token(newStaff("VET", branch)), id, body(kennel, 4)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(checkIn(foreign, id, body(kennel, 4)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(checkIn(receptionist, 999_999_999L, body(kennel, 4)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        data(checkIn(receptionist, id, body(kennel, 4)), HttpStatus.OK);
        assertError(checkIn(receptionist, id, body(kennel, 4)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void twoBookingsRacingForOneKennelOneWinsOneIsBrLt08() {
        long first = booked("2009-06-10", "2009-06-12");
        long counter = newCounterCustomer(null);
        long secondPet = newPet(counter, "DOG");
        long second = newBooking(counter, secondPet, branch, type, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-12"), "BOOKED", staffId);
        List<Supplier<ResponseEntity<Map<String, Object>>>> calls = List.of(
                () -> checkIn(receptionist, first, body(kennel, 4)),
                () -> checkIn(receptionist, second, body(kennel, 4)));

        List<ResponseEntity<Map<String, Object>>> results = racedUnderBranchLock(branch, calls);

        long ok = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertThat(ok).isEqualTo(1);
        ResponseEntity<Map<String, Object>> loser = results.stream().filter(r -> r.getStatusCode() != HttpStatus.OK)
                .findFirst().orElseThrow();
        assertRule(loser, "BR-LT-08");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarding_bookings WHERE kennel_id = ?", Integer.class,
                kennel)).isEqualTo(1);
    }

    @Test
    void failureWhileWritingTheCheckInRowRollsBackEverything() {
        long id = booked("2009-06-10", "2009-06-12");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION boarding_it_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'boarding it forced failure'; END $$""");
        jdbc.execute("CREATE TRIGGER boarding_it_fail BEFORE INSERT ON boarding_check_ins FOR EACH ROW "
                + "EXECUTE FUNCTION boarding_it_fail()");
        try {
            ResponseEntity<Map<String, Object>> response = checkIn(receptionist, id, body(kennel, 4));

            assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
            assertUntouched(id);
        } finally {
            jdbc.execute("DROP TRIGGER boarding_it_fail ON boarding_check_ins");
            jdbc.execute("DROP FUNCTION boarding_it_fail()");
        }
    }

    // ---------------------------------------------------------------- điều kiện nhận (#13)

    @Test
    void readinessListsGapsAndAvailableKennels() {
        long id = booked("2009-06-10", "2009-06-12");
        newKennel(branch, type, "C-00", "AVAILABLE");
        newKennel(branch, type, "C-09", "MAINTENANCE");

        Map<String, Object> ok = data(readiness(caretaker, id), HttpStatus.OK);

        assertThat(ok).containsEntry("withinOpeningHours", true).containsEntry("canCheckIn", true);
        assertThat(list(ok.get("availableKennels"))).extracting(k -> k.get("code")).containsExactly("C-00", "C-01");
        assertThat((List<?>) ok.get("vaccineGaps")).isEmpty();

        long vaccineType = jdbc.queryForObject("""
                INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id""", Long.class,
                "IT Dại " + java.util.UUID.randomUUID());
        GAPS.set(Map.of(pet, List.of(new VaccinationQueryApi.BoardingVaccineGap(vaccineType, null, null))));
        try {
            Map<String, Object> gap = data(readiness(receptionist, id), HttpStatus.OK);
            assertThat(gap).containsEntry("canCheckIn", false);
            assertThat((List<?>) gap.get("vaccineGaps")).hasSize(1);
        } finally {
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", vaccineType);
        }
    }

    @Test
    void readinessIsFalseOutsideHoursAndAfterTheDateAndConflictsWhenNotBooked() {
        long id = booked("2009-06-10", "2009-06-12");
        clock.set(Instant.parse("2009-06-10T13:00:00Z"));
        Map<String, Object> closed = data(readiness(receptionist, id), HttpStatus.OK);
        assertThat(closed).containsEntry("withinOpeningHours", false).containsEntry("canCheckIn", false);

        clock.set(Instant.parse("2009-06-11T03:00:00Z"));
        assertThat(data(readiness(token(staffId), id), HttpStatus.OK)).containsEntry("canCheckIn", false);

        clock.set(BoardingClockConfig.START);
        data(checkIn(receptionist, id, body(kennel, 4)), HttpStatus.OK);
        assertError(readiness(receptionist, id), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(readiness(token(customerAccount), id), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    // ---------------------------------------------------------------- hết chuồng (#15)

    @Test
    void cancelNoKennelCancelsAsClinicAndNotifiesCustomerAndManager() {
        long manager = newStaff("BRANCH_MANAGER", branch);
        jdbc.update("UPDATE kennels SET status = 'MAINTENANCE' WHERE id = ?", kennel);
        long id = booked("2009-06-10", "2009-06-12");

        Map<String, Object> data = data(cancelNoKennel(receptionist, id), HttpStatus.OK);

        assertThat(data).containsEntry("status", "CANCELLED").containsEntry("cancelSource", "CLINIC")
                .containsEntry("lateCancel", false);
        String code = (String) data.get("code");
        List<Map<String, Object>> outbox = jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id FROM notification_outbox
                WHERE payload ->> 'ma_dat_cho' = ?""", code);
        assertThat(outbox).extracting(r -> r.get("template_code")).containsExactlyInAnyOrder(
                "BOARDING_CANCELLED_BY_CLINIC", "BOARDING_CANCELLED_BY_CLINIC_APP", "BOARDING_NO_KENNEL_APP");
        assertThat(outbox).filteredOn(r -> "BOARDING_NO_KENNEL_APP".equals(r.get("template_code")))
                .singleElement().satisfies(r -> assertThat(((Number) r.get("recipient_account_id")).longValue())
                        .isEqualTo(manager));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customerId)).isZero();
    }

    @Test
    void cancelNoKennelRules() {
        long id = booked("2009-06-10", "2009-06-12");
        long future = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-20"),
                LocalDate.parse("2009-06-22"), "BOOKED", staffId);

        assertRule(cancelNoKennel(receptionist, id), "BR-LT-08");        // còn chuồng AVAILABLE
        jdbc.update("UPDATE kennels SET status = 'OCCUPIED' WHERE id = ?", kennel);
        assertRule(cancelNoKennel(receptionist, future), "BR-LT-08");    // chưa tới ngày nhận
        assertError(cancelNoKennel(caretaker, id), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(cancelNoKennel(receptionist, 999_999_999L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        data(cancelNoKennel(receptionist, id), HttpStatus.OK);
        assertError(cancelNoKennel(receptionist, id), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
