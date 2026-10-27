package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineOverdueService;
import com.petcare.support.MutableClock;

/**
 * Tranh chấp của ST18 quá hạn tái chủng (T39 phần 5, docs/adr/0083) với luồng khác trên cùng mũi / cùng thú, mỗi ca xác
 * nhận qua {@code pg_stat_activity} rằng bên đến sau thật sự đứng chờ:
 * <ul>
 * <li>dòng mũi đang bị luồng khác giữ → ST18 bỏ qua ngay (không chờ), lượt sau xét;</li>
 * <li>ghi mũi mới cùng loại (#19: {@code pets FOR SHARE} → {@code superseded_at} → hủy task) chạy đúng lúc ST18 đã khóa
 * dòng → #19 chờ ST18 commit rồi hủy luôn task {@code VACCINE_OVERDUE} ST18 vừa sinh;</li>
 * <li>đánh dấu thú đã mất giữ thú trước → ST18 chờ ở {@code pets FOR SHARE}, rồi thấy thú đã mất và không sinh task; ST18
 * giữ thú trước → bên đánh dấu mất chờ tới khi ST18 commit.</li>
 * </ul>
 * "ST18 đứng giữa transaction" dựng bằng trigger tạm làm UPDATE cờ {@code overdue_handled_at} chờ một advisory lock do
 * test giữ. Dùng chung Spring context và đồng hồ năm 1990 với {@link VaccineOverdueIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccineOverdueIT.TestBeans.class})
class VaccineOverdueRaceIT {

    private static final LocalDate TODAY = VaccineOverdueIT.TODAY;
    private static final Instant T0 = VaccineOverdueIT.T0;
    private static final int PAUSE_NAMESPACE = 4245;
    private static final String WAITING_ST18_FLAG = "%update vaccinations set overdue_handled_at%";
    private static final String WAITING_SUPERSEDE = "%update vaccinations set superseded_at%";
    private static final String WAITING_PET_SHARE = "%from pets%for share%";
    private static final String WAITING_PET_UPDATE = "%from pets%for no key update%";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private VaccineOverdueService service;
    @Autowired
    private VaccinationRepository vaccinations;
    @Autowired
    private PetQueryApi petQuery;
    @Autowired
    private CareTaskApi careTasks;

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
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_overdue_race_it_pause ON vaccinations");
        jdbc.execute("DROP FUNCTION IF EXISTS vaccine_overdue_race_it_pause()");
        fx.cleanUp();
    }

    /** Dòng mũi đang bị luồng khác khóa → ST18 trả {@code SKIPPED} ngay, không đợi (bỏ SKIP LOCKED thì quá 5 giây); lượt sau xét. */
    @Test
    void aDoseRowHeldByAnotherFlowIsSkippedWithoutWaitingAndRaisedNextRun() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        LocalDate cutoff = service.cutoff();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM vaccinations WHERE id = ? FOR UPDATE", dose);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        ReminderResult whileHeld = pool.submit(() -> service.raise(dose, cutoff)).get(5, TimeUnit.SECONDS);

        assertThat(whileHeld).isEqualTo(ReminderResult.SKIPPED);
        assertThat(handledAt(dose)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(service.raise(dose, cutoff)).isEqualTo(ReminderResult.TASK_CREATED);
        assertThat(fx.tasks(pet)).hasSize(1);
    }

    /**
     * ST18 đã khóa thú + dòng mũi cũ và đứng ở UPDATE cờ; #19 (mô phỏng đúng các bước của {@code VaccinationRecordService}:
     * {@code pets FOR SHARE} → {@code markSuperseded} → {@code cancelForRevaccination}) chờ ở {@code markSuperseded}. ST18
     * commit task {@code VACCINE_OVERDUE} → #19 chạy tiếp và hủy chính task đó (READ COMMITTED thấy dòng vừa commit).
     */
    @Test
    void recordingANewerDoseWhileSt18HoldsTheRowCancelsTheTaskSt18JustCreated() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        LocalDate cutoff = service.cutoff();
        installPause(dose);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st18 = pool.submit(() -> service.raise(dose, cutoff));
        awaitWaiting(WAITING_ST18_FLAG);
        Future<?> recordNewer = pool.submit(() -> tx.executeWithoutResult(s -> {
            petQuery.findPetForShare(pet);
            vaccinations.markSuperseded(List.of(dose), T0);
            careTasks.cancelForRevaccination(List.of(dose));
        }));
        awaitWaiting(WAITING_SUPERSEDE);
        assertThat(st18.isDone()).isFalse();
        assertThat(recordNewer.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(st18.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.TASK_CREATED);
        recordNewer.get(30, TimeUnit.SECONDS);
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("task_type", "VACCINE_OVERDUE").containsEntry("status", "CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE pet_id = ?", String.class, pet))
                .isNotBlank();
        assertThat(jdbc.queryForObject("SELECT handled_by FROM care_tasks WHERE pet_id = ?", Long.class, pet)).isNull();
        assertThat(handledAt(dose)).isNotNull();
    }

    /** #19 giữ dòng mũi trước → ST18 bỏ qua; mũi đã thay thế nên lượt sau cũng không xét (không task nào). */
    @Test
    void aDoseBeingSupersededIsSkippedAndNeverRaisedAfterwards() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        LocalDate cutoff = service.cutoff();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> recordNewer = pool.submit(() -> tx.executeWithoutResult(s -> {
            petQuery.findPetForShare(pet);
            vaccinations.markSuperseded(List.of(dose), T0);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        ReminderResult whileHeld = pool.submit(() -> service.raise(dose, cutoff)).get(5, TimeUnit.SECONDS);
        release.countDown();
        recordNewer.get(10, TimeUnit.SECONDS);

        assertThat(whileHeld).isEqualTo(ReminderResult.SKIPPED);
        assertThat(service.raise(dose, cutoff)).isEqualTo(ReminderResult.SKIPPED);
        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(handledAt(dose)).isNull();
    }

    /** Bên đánh dấu mất giữ thú trước → ST18 chờ ở {@code pets FOR SHARE}, rồi thấy thú đã mất và không sinh task. */
    @Test
    void st18WaitsForAPetBeingMarkedDeceasedAndThenCreatesNothing() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        LocalDate cutoff = service.cutoff();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> marking = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet);
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st18 = pool.submit(() -> service.raise(dose, cutoff));
        awaitWaiting(WAITING_PET_SHARE);
        assertThat(st18.isDone()).isFalse();

        release.countDown();
        marking.get(10, TimeUnit.SECONDS);

        assertThat(st18.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.NOT_SENT);
        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(handledAt(dose)).isNotNull();
    }

    /**
     * ST18 giữ thú trước → bên đánh dấu mất chờ tới khi ST18 commit, rồi mới đánh dấu. Task {@code VACCINE_OVERDUE} ST18
     * vừa sinh còn {@code OPEN} (ST19 hủy task khi thú mất chưa làm — T39 phần sau), nhưng không bao giờ có task sinh SAU
     * khi thú đã được đánh dấu mất.
     */
    @Test
    void markingAPetDeceasedWaitsForSt18ThatAlreadyHoldsThePet() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        LocalDate cutoff = service.cutoff();
        installPause(dose);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st18 = pool.submit(() -> service.raise(dose, cutoff));
        awaitWaiting(WAITING_ST18_FLAG);
        Future<?> marking = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet);
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
        }));
        awaitWaiting(WAITING_PET_UPDATE);
        assertThat(st18.isDone()).isFalse();
        assertThat(marking.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(st18.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.TASK_CREATED);
        marking.get(30, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", java.sql.Date.class, pet)).isNotNull();
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
    }

    // ================================================================ helpers

    /** Trigger tạm: UPDATE đặt {@code overdue_handled_at} của mũi {@code dose} chờ advisory {@value #PAUSE_NAMESPACE}. */
    private void installPause(long dose) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION vaccine_overdue_race_it_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(%d, 1); RETURN NEW; END $$""".formatted(PAUSE_NAMESPACE));
        jdbc.execute("CREATE TRIGGER vaccine_overdue_race_it_pause BEFORE UPDATE ON vaccinations FOR EACH ROW WHEN "
                + "(NEW.id = " + dose + " AND OLD.overdue_handled_at IS NULL AND NEW.overdue_handled_at IS NOT NULL) "
                + "EXECUTE FUNCTION vaccine_overdue_race_it_pause()");
    }

    private Instant handledAt(long dose) {
        Timestamp at = jdbc.queryForObject("SELECT overdue_handled_at FROM vaccinations WHERE id = ?", Timestamp.class,
                dose);
        return at == null ? null : at.toInstant();
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
