package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.module.appointment.service.AppointmentNoShowService;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.appointment.service.NoShowCutoff;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * ST05 {@code NO_SHOW} (T25 phần 2, docs/adr/0048) trên Postgres 17 thật. Đồng hồ {@link MutableClock} ở <b>năm 2000</b>
 * và mọi lịch của test này ở tháng 5/2000, nên job (quét theo cutoff 2000) không bao giờ đụng lịch của IT khác. Job chạy
 * qua {@code run()} thật; {@code BoardingQueryApi} là {@code @MockitoBean} (còn placeholder — D033).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NoShowIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class NoShowIT {

    private static final LocalDate DAY = LocalDate.of(2000, 6, 1);
    private static final Instant T0 = vn(DAY, LocalTime.of(10, 30));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @MockitoBean
    private BoardingQueryApi boardings;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private NoShowJob job;
    @Autowired
    private AppointmentNoShowService service;
    @Autowired
    private BookingRestrictionApi restrictions;
    @Autowired
    private ConfigurableListableBeanFactory beans;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long staff;
    private long customer;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        clock.set(T0);
        reset(boardings);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        staff = data.receptionist();
        customer = data.counterCustomer();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_noshow_it_update ON appointments");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_noshow_it_restriction ON booking_restrictions");
        jdbc.execute("DROP FUNCTION IF EXISTS noshow_it_fail()");
        data.cleanUp();
    }

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private long booked(long ownerCustomer, LocalDate date, LocalTime start, String status) {
        long pet = data.pet(ownerCustomer);
        return data.appointment(ownerCustomer, pet, branch, medical, "MEDICAL", date, start, status, staff);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private int restrictionCount(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Integer.class,
                customerId);
    }

    private NoShowCutoff cutoff() {
        return service.cutoff(clock.instant());
    }

    /** Toàn bộ cột trừ {@code status}, {@code updated_at}. */
    private String rowWithoutStatus(long id) {
        return jdbc.queryForObject("SELECT (to_jsonb(a) - 'status' - 'updated_at')::text FROM appointments a WHERE id = ?",
                String.class, id);
    }

    // --- nghiệp vụ -----------------------------------------------------------------------------------------------

    @Test
    void overdueBookedBecomesNoShowAndOnlyStatusChanges() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        String before = rowWithoutStatus(id);

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(rowWithoutStatus(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT late_cancel FROM appointments WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(restrictionCount(customer)).isZero();
    }

    @Test
    void notOverdueAndOtherStatusesAreUntouched() {
        long future = booked(customer, LocalDate.of(2000, 6, 1), LocalTime.of(11, 0), "BOOKED");
        long checkedIn = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "CHECKED_IN");
        long cancelled = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "COMPLETED");

        job.run();

        assertThat(status(future)).isEqualTo("BOOKED");
        assertThat(status(checkedIn)).isEqualTo("CHECKED_IN");
        assertThat(status(cancelled)).isEqualTo("COMPLETED");
    }

    @Test
    void boundaryExactlyNoShowAfterStaysBookedOneMicrosecondLaterIsNoShow() {
        long id = booked(customer, DAY, LocalTime.of(9, 0), "BOOKED");

        clock.set(vn(DAY, LocalTime.of(9, 30)));
        job.run();
        assertThat(status(id)).isEqualTo("BOOKED");

        clock.set(vn(DAY, LocalTime.of(9, 30)).plusNanos(1_000));
        job.run();
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void thirdViolationCreatesExactlyOneRestriction() {
        long a = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");
        long later = booked(customer, LocalDate.of(2000, 6, 5), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(List.of(status(a), status(b), status(c))).containsOnly("NO_SHOW");
        assertThat(status(later)).as("lịch đã đặt trước đó giữ nguyên").isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT violation_count FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customer)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT lifted_at IS NULL FROM booking_restrictions WHERE customer_id = ?",
                Boolean.class, customer)).isTrue();
        Boolean restricted = tx.execute(s -> restrictions.isRestricted(customer, clock.instant()));
        assertThat(restricted).isTrue();
    }

    @Test
    void lockedAccountCustomerStillCounted() {
        long account = data.customerAccount("noshow-" + System.nanoTime() + "@example.com", "ACTIVE");
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'test' WHERE id = ?", account);
        long online = data.onlineCustomer(account, "Khách bị khóa");
        long a = booked(online, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(online, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(online, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(List.of(status(a), status(b), status(c))).containsOnly("NO_SHOW");
        assertThat(restrictionCount(online)).isEqualTo(1);
    }

    @Test
    void freesPetLimitAndQuota() {
        long pet = data.pet(customer);
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2000, 5, 20),
                LocalTime.of(9, 0), "BOOKED", staff);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Integer.class, pet);

        job.run();

        assertThat(before).isEqualTo(1);
        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Integer.class, pet)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE id = ? AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')""",
                Integer.class, id)).as("không còn chiếm quota").isZero();
    }

    // --- commit / rollback -----------------------------------------------------------------------------------------

    @Test
    void evaluationFailureRollsBackNoShowAndKeepsExceptionType(CapturedOutput output) {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long updatedBefore = jdbc.queryForObject("SELECT extract(epoch FROM updated_at)::bigint FROM appointments WHERE id = ?",
                Long.class, id);
        doThrow(new UnsupportedOperationException("D033")).when(boardings).countViolations(anyLong(), any());

        assertThatThrownBy(() -> service.markNoShow(id, cutoff())).isInstanceOf(UnsupportedOperationException.class);
        job.run();

        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM updated_at)::bigint FROM appointments WHERE id = ?",
                Long.class, id)).isEqualTo(updatedBefore);
        assertThat(output).contains("NO_SHOW_FAILED appointmentId=" + id);
        assertAdvisoryLocksReleased();
    }

    @Test
    void failedAppointmentDoesNotBlockOthers() {
        long failing = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long otherCustomer = data.counterCustomer();
        long fine = booked(otherCustomer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        doThrow(new UnsupportedOperationException("D033")).when(boardings).countViolations(
                org.mockito.ArgumentMatchers.eq(customer), any());

        job.run();

        assertThat(status(failing)).isEqualTo("BOOKED");
        assertThat(status(fine)).isEqualTo("NO_SHOW");
    }

    @Test
    void updateFailureLeavesBooked() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        failingTrigger("trg_noshow_it_update", "appointments", "UPDATE", "NEW.customer_id = " + customer);

        job.run();

        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
    }

    @Test
    void restrictionInsertFailureRollsBackTheFlushedNoShow() {
        long a = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");
        failingTrigger("trg_noshow_it_restriction", "booking_restrictions", "INSERT", "NEW.customer_id = " + customer);

        job.run();

        assertThat(status(a)).isEqualTo("NO_SHOW");
        assertThat(status(b)).isEqualTo("NO_SHOW");
        assertThat(status(c)).as("lịch thứ 3 gây hạn chế: INSERT lỗi nên rollback cả NO_SHOW").isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
    }

    // --- race ------------------------------------------------------------------------------------------------------

    @Test
    void rowHeldByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
            held.countDown();
            await(release);
            jdbc.update("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?", id);
        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            NoShowResult result = service.markNoShow(id, cutoff());

            assertThat(result).isEqualTo(NoShowResult.SKIPPED);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        job.run();
        assertThat(status(id)).as("check-in thắng, lượt sau bỏ qua").isEqualTo("CHECKED_IN");
    }

    @Test
    void twoConcurrentMarksOfSameAppointmentMarkOnce() throws Exception {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        NoShowCutoff cutoff = cutoff();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<NoShowResult> first = pool.submit(() -> {
                await(start);
                return service.markNoShow(id, cutoff);
            });
            Future<NoShowResult> second = pool.submit(() -> {
                await(start);
                return service.markNoShow(id, cutoff);
            });
            start.countDown();
            List<NoShowResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(NoShowResult.MARKED, NoShowResult.SKIPPED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void rowThatLeftBookedIsSkippedNotAnError() {
        long checkedIn = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "CHECKED_IN");
        long noShow = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "NO_SHOW");
        long cancelled = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "CANCELLED");
        jdbc.update("UPDATE appointments SET cancel_source = 'CUSTOMER', cancelled_at = now() WHERE id = ?", cancelled);
        NoShowCutoff cutoff = cutoff();

        assertThat(service.markNoShow(checkedIn, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(service.markNoShow(noShow, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(service.markNoShow(cancelled, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(status(checkedIn)).isEqualTo("CHECKED_IN");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void slotAtExactlyTheCutoffIsNotSelectedByTheQueries() {
        long id = booked(customer, DAY, LocalTime.of(9, 0), "BOOKED");
        clock.set(vn(DAY, LocalTime.of(9, 30)));
        NoShowCutoff cutoff = cutoff();

        assertThat(service.findOverdueIds(cutoff, 0, 100)).doesNotContain(id);
        assertThat(service.markNoShow(id, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    @Test
    void rerunChangesNothing() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        job.run();
        String after = rowWithoutStatus(id);

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(rowWithoutStatus(id)).isEqualTo(after);
    }

    // --- phạm vi truy cập ------------------------------------------------------------------------------------------

    @Test
    void jobRunsWithoutSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void onlyTheJobDependsOnTheService() {
        String[] dependents = beans.getDependentBeans("appointmentNoShowService");

        assertThat(dependents).filteredOn(name -> !name.contains("NoShowIT")).containsExactly("noShowJob");
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private void failingTrigger(String name, String table, String event, String when) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION noshow_it_fail() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'noshow_it: %s bị chặn'; END $$ LANGUAGE plpgsql""".formatted(event));
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table
                + " FOR EACH ROW WHEN (" + when + ") EXECUTE FUNCTION noshow_it_fail()");
    }

    private void assertAdvisoryLocksReleased() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 1002 AND granted""",
                Integer.class)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
