package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.api.FollowUpApi;
import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.FollowUpReminderService;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Race giữa ST04 nhắc tái khám và {@link FollowUpApi} (docs/adr/0084) cùng khóa dòng {@code medical_records}, khuôn
 * {@link VaccineReminderRaceIT} ({@code whileHeld}: giữ khóa ở luồng khác, chờ {@code pg_stat_activity}). Hai thứ tự:
 * ST04 giữ dòng trước (hủy phải chờ, rồi thấy và hủy task ST04 vừa commit) và hủy giữ dòng trước (ST04 {@code SKIP LOCKED}
 * bỏ qua ngay, không gửi, không task).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FollowUpCancellationIT.TestBeans.class})
class FollowUpCancellationRaceIT {

    private static final LocalDate TODAY = LocalDate.of(2023, 6, 1);
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final Instant PAST = T0.minusSeconds(40L * 86_400);
    private static final int PAUSE_NAMESPACE = 4245;
    private static final String WAITING_ST04_FLAG = "%update medical_records set follow_up_reminded_at%";
    private static final String WAITING_CANCEL = "%update medical_records m set follow_up_cancelled_at%";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FollowUpReminderService reminders;
    @Autowired
    private FollowUpApi followUps;

    private TransactionTemplate tx;
    private ReminderItFixture fx;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        fx = new ReminderItFixture(jdbc);
        fx.setUp();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        jdbc.execute("DROP TRIGGER IF EXISTS follow_up_race_it_pause ON medical_records");
        jdbc.execute("DROP FUNCTION IF EXISTS follow_up_race_it_pause()");
        fx.cleanUp();
    }

    /**
     * ST04 đã khóa dòng bệnh án và đứng ở UPDATE cờ "đã nhắc"; bước hủy chờ ở UPDATE đầu tiên. ST04 commit nhắc + task
     * {@code FOLLOW_UP_DUE} → hủy chạy tiếp, UPDATE bỏ qua dòng vừa nhắc, bước đọc (READ COMMITTED) thấy dòng đó và hủy chính
     * task ST04 vừa sinh. Đọc id bệnh án đã nhắc TRƯỚC khi UPDATE thì task này còn {@code OPEN}.
     */
    @Test
    void cancellationWaitingForSt04CancelsTheTaskSt04JustCreated() throws Exception {
        long pet = fx.pet(fx.silent);
        long visit = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(3), true,
                false);
        DueDateWindow window = reminders.window();
        installPause(visit);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st04 = pool.submit(() -> reminders.remind(visit, window));
        awaitWaiting(WAITING_ST04_FLAG);
        Future<?> cancel = pool.submit(() -> tx.executeWithoutResult(s -> followUps.cancelPendingFollowUps(pet, T0)));
        awaitWaiting(WAITING_CANCEL);
        assertThat(st04.isDone()).isFalse();
        assertThat(cancel.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(st04.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.TASK_CREATED);
        cancel.get(30, TimeUnit.SECONDS);
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("task_type", "FOLLOW_UP_DUE").containsEntry("status", "CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT handled_by FROM care_tasks WHERE pet_id = ?", Long.class, pet)).isNull();
        assertThat(FollowUpItSupport.cancelledAt(jdbc, visit)).as("đã nhắc rồi: không đặt cờ hủy nhắc").isNull();
        assertThat(jdbc.queryForObject("SELECT follow_up_reminded_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit)).isNotNull();
    }

    /** Hủy giữ dòng trước → ST04 {@code SKIP LOCKED} trả {@code SKIPPED} ngay (quá 5 giây là đang chờ khóa), rồi vẫn bỏ qua. */
    @Test
    void st04IsSkippedWithoutWaitingWhileTheCancellationHoldsTheRow() throws Exception {
        long pet = fx.pet(fx.silent);
        long visit = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(3), true,
                false);
        DueDateWindow window = reminders.window();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> cancel = pool.submit(() -> tx.executeWithoutResult(s -> {
            followUps.cancelPendingFollowUps(pet, T0);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        ReminderResult whileHeld = pool.submit(() -> reminders.remind(visit, window)).get(5, TimeUnit.SECONDS);

        assertThat(whileHeld).isEqualTo(ReminderResult.SKIPPED);
        release.countDown();
        cancel.get(10, TimeUnit.SECONDS);
        assertThat(reminders.remind(visit, window)).isEqualTo(ReminderResult.SKIPPED);
        assertThat(FollowUpItSupport.cancelledAt(jdbc, visit)).isEqualTo(T0);
        assertThat(fx.outbox(pet, "FOLLOW_UP_REMINDER", "FOLLOW_UP_REMINDER_APP")).isEmpty();
        assertThat(fx.tasks(pet)).isEmpty();
    }

    // ================================================================ helpers

    /** Trigger tạm: UPDATE đặt {@code follow_up_reminded_at} của bệnh án {@code visit} chờ advisory {@value #PAUSE_NAMESPACE}. */
    private void installPause(long visit) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION follow_up_race_it_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(%d, 1); RETURN NEW; END $$""".formatted(PAUSE_NAMESPACE));
        jdbc.execute("CREATE TRIGGER follow_up_race_it_pause BEFORE UPDATE ON medical_records FOR EACH ROW WHEN "
                + "(NEW.visit_id = " + visit + " AND OLD.follow_up_reminded_at IS NULL "
                + "AND NEW.follow_up_reminded_at IS NOT NULL) EXECUTE FUNCTION follow_up_race_it_pause()");
    }

    /** Chờ tới khi có ít nhất một câu khớp {@code queryLike} đứng chờ khóa. */
    private void awaitWaiting(String queryLike) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Integer.class, queryLike);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy câu chờ khóa khớp " + queryLike);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ chốt");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
