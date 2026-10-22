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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.job.BoardingNoShowJob;

/** ST05 cho lưu trú (Đặt chỗ#6, BR-LT-06; docs/adr/0065) qua {@code run()} thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class BoardingNoShowIT extends BoardingItSupport {

    @Autowired
    private BoardingNoShowJob job;

    private long branch;
    private long type;
    private long staffId;
    private long customerId;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        newKennel(branch, type, "C-01", "AVAILABLE");
        staffId = newStaff("RECEPTIONIST", branch);
        customerId = newCounterCustomer(null);
    }

    private long booked(String in, String out) {
        return newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse(in),
                LocalDate.parse(out), "BOOKED", staffId);
    }

    @Test
    void staysBookedUntilClosingTimeOfCheckInDayThenBecomesNoShow() {
        long id = booked("2009-06-10", "2009-06-12");

        clock.set(Instant.parse("2009-06-10T11:59:59Z"));
        job.run();
        assertThat(statusOf(id)).as("18:59:59 giờ VN, trước giờ đóng cửa 19:00").isEqualTo("BOOKED");

        clock.set(Instant.parse("2009-06-10T12:00:00Z"));
        job.run();
        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void pastCheckInDaysAreMarked() {
        long id = booked("2009-06-05", "2009-06-07");

        job.run();

        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void futureCheckInIsUntouched() {
        long id = booked("2009-06-11", "2009-06-12");
        clock.set(Instant.parse("2009-06-10T16:59:00Z"));

        job.run();

        assertThat(statusOf(id)).isEqualTo("BOOKED");
    }

    @Test
    void dayWithoutOpeningHoursUsesNextMidnight() {
        jdbc.update("DELETE FROM opening_hours WHERE branch_id = ?", branch);
        long id = booked("2009-06-10", "2009-06-12");

        job.run();
        assertThat(statusOf(id)).isEqualTo("BOOKED");

        clock.set(Instant.parse("2009-06-10T17:00:00Z"));
        job.run();
        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void lockedRowIsSkippedThenMarkedOnTheNextRun() throws Exception {
        long id = booked("2009-06-05", "2009-06-07");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM boarding_bookings WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            job.run();
            assertThat(statusOf(id)).isEqualTo("BOOKED");

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        job.run();
        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void cancelInFlightWinsAndNoViolationIsCounted() throws Exception {
        long id = booked("2009-06-05", "2009-06-07");
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.update("UPDATE boarding_bookings SET status = 'CANCELLED', cancel_source = 'STAFF' WHERE id = ?",
                        id);
                cancelled.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(cancelled.await(30, TimeUnit.SECONDS)).isTrue();

            job.run();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        job.run();

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                customerId)).isZero();
    }

    @Test
    void thirdViolationCreatesRestrictionStartingAtTheStoredUpdatedAt() {
        for (int i = 0; i < 2; i++) {
            long old = newBooking(customerId, newPet(customerId, "DOG"), branch, type, LocalDate.parse("2009-06-01"),
                    LocalDate.parse("2009-06-02"), "NO_SHOW", staffId);
            jdbc.update("UPDATE boarding_bookings SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2009-06-03T00:00:00Z")), old);
        }
        long id = booked("2009-06-05", "2009-06-07");

        job.run();

        assertThat(statusOf(id)).isEqualTo("NO_SHOW");
        Map<String, Object> restriction = jdbc.queryForMap(
                "SELECT starts_at, violation_count FROM booking_restrictions WHERE customer_id = ?", customerId);
        assertThat(((Number) restriction.get("violation_count")).intValue()).isEqualTo(3);
        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM boarding_bookings WHERE id = ?",
                Timestamp.class, id).toInstant();
        assertThat(((Timestamp) restriction.get("starts_at")).toInstant()).isEqualTo(updatedAt);
    }

    @Test
    void scanUsesThePartialIndexOnRealisticData() {
        long pet = newPet(customerId, "DOG");
        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.update("""
                    INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                            check_out_date, nightly_price, status, channel, booked_by, cancel_source)
                    SELECT 'LTX-' || g, ?, ?, ?, ?, DATE '2009-05-01' + (g % 30), DATE '2009-05-02' + (g % 30),
                           150000, 'CANCELLED', 'COUNTER', ?, 'STAFF'
                    FROM generate_series(1, 20000) g""", customerId, pet, branch, type, staffId);
            jdbc.execute("ANALYZE boarding_bookings");
            List<String> rows = jdbc.queryForList("""
                    EXPLAIN SELECT id AS id, check_in_date AS checkInDate FROM boarding_bookings
                    WHERE status = 'BOOKED' AND check_in_date <= DATE '2009-06-10'
                    ORDER BY check_in_date, id LIMIT 100""", String.class);
            status.setRollbackOnly();
            return String.join("\n", rows);
        });

        assertThat(plan).contains("ix_boarding_bookings_booked_check_in");
    }
}
