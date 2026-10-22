package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.api.VaccinationQueryApi;

/** Đặt chỗ lưu trú (boarding-v1 #8, UC58, Đặt chỗ#1; docs/adr/0065). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class, BoardingBookingIT.TestBeans.class})
class BoardingBookingIT extends BoardingItSupport {

    /** Stand-in cho {@code VaccinationQueryApi} của BE-1 (T28 chưa giao): trả khoảng trống đặt bởi từng test. */
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
            };
        }
    }

    private long branch;
    private long type;
    private long staffId;
    private String receptionist;
    private long customerId;
    private String customer;
    private long pet;

    @BeforeEach
    void setUp() {
        GAPS.set(Map.of());
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, type, "C-01", "AVAILABLE");
        newKennel(branch, type, "C-02", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        receptionist = token(staffId);
        long[] online = newOnlineCustomer();
        customerId = online[1];
        customer = token(online[0]);
        pet = newPet(customerId, "DOG");
        weigh(pet, "12.5");
    }

    private ResponseEntity<Map<String, Object>> book(String token, long petId, long branchId, long kennelType,
            String in, String out) {
        return call(HttpMethod.POST, "/api/boarding-bookings", token,
                Map.of("petId", petId, "branchId", branchId, "kennelTypeId", kennelType, "checkInDate", in,
                        "checkOutDate", out));
    }

    @Test
    void customerBooksOnlineWithSnapshotPriceAndVaccineWarnings() {
        jdbc.update("INSERT INTO vaccine_types (name, species) VALUES ('Dại IT', 'DOG')");
        long vaccineType = jdbc.queryForObject("SELECT max(id) FROM vaccine_types WHERE name = 'Dại IT'", Long.class);
        GAPS.set(Map.of(pet, List.of(new VaccinationQueryApi.BoardingVaccineGap(vaccineType, null, null))));
        try {
            Map<String, Object> result = data(book(customer, pet, branch, type, "2009-06-12", "2009-06-15"),
                    HttpStatus.CREATED);

            Map<String, Object> booking = map(result.get("booking"));
            assertThat(booking).containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                    .containsEntry("lateCancel", false).containsEntry("missingCareLogToday", false);
            assertThat((String) booking.get("code")).matches("^LT-0906-\\d{5}$");
            assertThat(number(booking.get("nightlyPrice"))).isEqualTo(150_000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("vaccineWarnings");
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).containsEntry("vaccineTypeName", "Dại IT");

            jdbc.update("UPDATE services SET price = 999000 WHERE id = ?", type);
            assertThat(jdbc.queryForObject("SELECT nightly_price FROM boarding_bookings WHERE id = ?", Long.class,
                    number(booking.get("bookingId")))).as("snapshot giữ giá lúc đặt").isEqualTo(150_000L);
        } finally {
            jdbc.update("DELETE FROM boarding_bookings WHERE pet_id = ?", pet);
            jdbc.update("DELETE FROM vaccine_types WHERE name = 'Dại IT'");
        }
    }

    @Test
    void receptionistBooksSameDayBeforeClosingButNotAfter() {
        long counter = newCounterCustomer(null);
        long counterPet = newPet(counter, "DOG");

        assertThat(book(receptionist, counterPet, branch, type, "2009-06-10", "2009-06-11").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        clock.set(Instant.parse("2009-06-10T12:00:00Z"));
        long other = newPet(counter, "DOG");
        assertRule(book(receptionist, other, branch, type, "2009-06-10", "2009-06-11"), "BR-LT-04");
    }

    @Test
    void wrongSpeciesOverweightAndOverlapAreBrLt02() {
        long cat = newPet(customerId, "CAT");
        long heavy = newPet(customerId, "DOG");
        weigh(heavy, "31");

        assertRule(book(customer, cat, branch, type, "2009-06-12", "2009-06-14"), "BR-LT-02");
        assertRule(book(customer, heavy, branch, type, "2009-06-12", "2009-06-14"), "BR-LT-02");
        data(book(customer, pet, branch, type, "2009-06-12", "2009-06-14"), HttpStatus.CREATED);
        assertRule(book(customer, pet, branch, type, "2009-06-13", "2009-06-15"), "BR-LT-02");
        assertRule(book(customer, pet, branch, type, "2009-06-20", "2009-07-25"), "BR-LT-02");
    }

    @Test
    void holidayOnCheckOutDayIsBrLt04() {
        holiday(branch, LocalDate.parse("2009-06-14"), staffId);

        assertRule(book(customer, pet, branch, type, "2009-06-12", "2009-06-14"), "BR-LT-04");
    }

    @Test
    void customerWindowIsEnforcedButUnweighedPetIsAllowed() {
        assertRule(book(customer, pet, branch, type, "2009-06-10", "2009-06-12"), "BR-LT-04");
        assertRule(book(customer, pet, branch, type, "2009-09-01", "2009-09-03"), "BR-LT-04");
        long unweighed = newPet(customerId, "DOG");

        assertThat(book(customer, unweighed, branch, type, "2009-06-12", "2009-06-13").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deceasedPetPendingLinkAndRestrictionAreRejected() {
        long dead = newPet(customerId, "DOG");
        jdbc.update("UPDATE pets SET deceased_on = DATE '2009-06-01' WHERE id = ?", dead);
        assertRule(book(customer, dead, branch, type, "2009-06-12", "2009-06-14"), "BR-KH-05");

        jdbc.update("INSERT INTO booking_restrictions (customer_id, starts_at, ends_at, violation_count) "
                + "VALUES (?, ?, ?, 3)", customerId, Timestamp.from(Instant.parse("2009-06-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2009-07-01T00:00:00Z")));
        assertRule(book(customer, pet, branch, type, "2009-06-12", "2009-06-14"), "BR-LH-09");
        assertThat(book(receptionist, pet, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .as("hạn chế chỉ áp cho đặt online").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void otherCustomersPetIsNotFoundAndOtherBranchReceptionistIsForbidden() {
        long other = newCounterCustomer(null);
        long otherPet = newPet(other, "DOG");
        long otherBranch = newBranch();
        String foreign = token(newStaff("RECEPTIONIST", otherBranch));

        assertThat(book(customer, otherPet, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(book(foreign, pet, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(book(null, pet, branch, type, "2009-06-12", "2009-06-14").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void lastNightRaceOneWinsOneIsBrLt03() {
        jdbc.update("UPDATE kennels SET status = 'MAINTENANCE' WHERE branch_id = ? AND code = 'C-02'", branch);
        long counter = newCounterCustomer(null);
        long petA = newPet(counter, "DOG");
        long petB = newPet(counter, "DOG");
        List<Supplier<ResponseEntity<Map<String, Object>>>> calls = List.of(
                () -> book(receptionist, petA, branch, type, "2009-06-12", "2009-06-14"),
                () -> book(receptionist, petB, branch, type, "2009-06-12", "2009-06-14"));

        List<ResponseEntity<Map<String, Object>>> results = racedUnderBranchLock(branch, calls);

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 400);
        ResponseEntity<Map<String, Object>> loser = results.stream().filter(r -> r.getStatusCode().value() == 400)
                .findFirst().orElseThrow();
        assertRule(loser, "BR-LT-03");
        assertThat((String) loser.getBody().get("message")).contains("12/06, 13/06");
    }

    @Test
    void bookingWaitsForPetDeceasedAndThenIsBrKh05() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", Long.class, pet);
                jdbc.update("UPDATE pets SET deceased_on = DATE '2009-06-10' WHERE id = ?", pet);
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> booking = pool.submit(
                    () -> book(receptionist, pet, branch, type, "2009-06-12", "2009-06-14"));
            waitForLockWaiter("%pets%");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertRule(booking.get(30, TimeUnit.SECONDS), "BR-KH-05");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void samePetOverlappingInTwoBranchesConcurrentlyOneWins() throws Exception {
        long otherBranch = newBranch();
        long otherType = newKennelType(otherBranch, "DOG", "30", 150_000);
        newKennel(otherBranch, otherType, "X-01", "AVAILABLE");
        String otherReceptionist = token(newStaff("RECEPTIONIST", otherBranch));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                newBooking(customerId, pet, branch, type, LocalDate.parse("2009-06-12"),
                        LocalDate.parse("2009-06-14"), "BOOKED", staffId);
                inserted.countDown();
                await(release);
                return null;
            }));
            assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                    () -> book(otherReceptionist, pet, otherBranch, otherType, "2009-06-13", "2009-06-15"));
            waitForLockWaiter("%boarding_bookings%");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertThat(second.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM boarding_bookings WHERE pet_id = ?", Long.class,
                    pet)).isEqualTo(1L);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @AfterEach
    void clearGaps() {
        GAPS.set(Map.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitForLockWaiter(String queryPattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT CAST(count(*) AS integer) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE ?""", Integer.class, queryPattern);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Không thấy lời gọi nào chờ khóa " + queryPattern);
    }
}
