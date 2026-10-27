package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineReminderService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Tranh chấp của ST04 nhắc tái chủng (T39 phần 3, docs/adr/0082) với luồng khác trên cùng mũi / cùng thú, mỗi ca xác nhận
 * qua {@code pg_stat_activity} rằng bên đến sau thật sự đứng chờ:
 * <ul>
 * <li>dòng mũi đang bị luồng khác giữ → ST04 bỏ qua ngay (không chờ), lượt sau nhắc;</li>
 * <li>ghi mũi mới cùng loại (#19: {@code pets FOR SHARE} → {@code superseded_at} → hủy task) chạy đúng lúc ST04 đã khóa
 * dòng → #19 chờ ST04 commit rồi hủy luôn task ST04 vừa sinh;</li>
 * <li>đánh dấu thú đã mất ({@code pets FOR NO KEY UPDATE}) giữ thú trước → ST04 chờ ở {@code pets FOR SHARE}, rồi thấy thú
 * đã mất và không gửi gì; ST04 giữ thú trước → bên đánh dấu mất chờ tới khi ST04 commit.</li>
 * </ul>
 * "ST04 đứng giữa transaction" dựng bằng trigger tạm làm UPDATE cờ {@code due_reminded_at} chờ một advisory lock do test giữ
 * (ST04 đã khóa thú và dòng mũi tại thời điểm đó). Dùng chung Spring context và đồng hồ năm 2021 với
 * {@link VaccineReminderIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccineReminderIT.TestBeans.class})
class VaccineReminderRaceIT {

    private static final LocalDate TODAY = LocalDate.of(2021, 6, 1);
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final int PAUSE_NAMESPACE = 4244;
    private static final String WAITING_ST04_FLAG = "%update vaccinations set due_reminded_at%";
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
    private VaccineReminderService service;
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
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_race_it_pause ON vaccinations");
        jdbc.execute("DROP FUNCTION IF EXISTS vaccine_race_it_pause()");
        fx.cleanUp();
    }

    /** Dòng mũi đang bị luồng khác khóa → ST04 trả {@code SKIPPED} ngay, không đợi (bỏ SKIP LOCKED thì quá 5 giây); lượt sau nhắc. */
    @Test
    void aDoseRowHeldByAnotherFlowIsSkippedWithoutWaitingAndRemindedNextRun() throws Exception {
        long pet = fx.pet(fx.counter);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.plusDays(3));
        DueDateWindow window = service.window();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM vaccinations WHERE id = ? FOR UPDATE", dose);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        ReminderResult whileHeld = pool.submit(() -> service.remind(dose, window)).get(5, TimeUnit.SECONDS);

        assertThat(whileHeld).isEqualTo(ReminderResult.SKIPPED);
        assertThat(remindedAt(dose)).isNull();
        assertThat(fx.outbox(pet, "VACCINE_REMINDER")).isEmpty();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(service.remind(dose, window)).isEqualTo(ReminderResult.REMINDED);
        assertThat(fx.outbox(pet, "VACCINE_REMINDER")).hasSize(1);
    }

    /**
     * ST04 đã khóa thú + dòng mũi cũ và đứng ở UPDATE cờ; #19 (mô phỏng đúng các bước của {@code VaccinationRecordService}:
     * {@code pets FOR SHARE} → {@code markSuperseded} → {@code cancelForRevaccination}) chờ ở {@code markSuperseded}. ST04
     * commit task {@code VACCINE_DUE} → #19 chạy tiếp và hủy chính task đó (READ COMMITTED thấy dòng vừa commit).
     */
    @Test
    void recordingANewerDoseWhileSt04HoldsTheRowCancelsTheTaskSt04JustCreated() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.plusDays(3));
        DueDateWindow window = service.window();
        installPause(dose);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st04 = pool.submit(() -> service.remind(dose, window));
        awaitWaiting(WAITING_ST04_FLAG);
        Future<?> recordNewer = pool.submit(() -> tx.executeWithoutResult(s -> {
            petQuery.findPetForShare(pet);
            vaccinations.markSuperseded(List.of(dose), T0);
            careTasks.cancelForRevaccination(List.of(dose));
        }));
        awaitWaiting(WAITING_SUPERSEDE);
        assertThat(st04.isDone()).isFalse();
        assertThat(recordNewer.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(st04.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.TASK_CREATED);
        recordNewer.get(30, TimeUnit.SECONDS);
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("task_type", "VACCINE_DUE").containsEntry("status", "CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE pet_id = ?", String.class, pet))
                .isNotBlank();
        assertThat(jdbc.queryForObject("SELECT handled_by FROM care_tasks WHERE pet_id = ?", Long.class, pet)).isNull();
        assertThat(remindedAt(dose)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT superseded_at FROM vaccinations WHERE id = ?", Timestamp.class, dose))
                .isNotNull();
    }

    /** Đánh dấu thú đã mất giữ thú trước → ST04 chờ ở {@code pets FOR SHARE}, sau đó thấy thú đã mất: không gửi, không task. */
    @Test
    void st04WaitsForAPetBeingMarkedDeceasedAndThenSendsNothing() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.plusDays(3));
        DueDateWindow window = service.window();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> marking = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet);
            held.countDown();
            await(release);
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st04 = pool.submit(() -> service.remind(dose, window));
        awaitWaiting(WAITING_PET_SHARE);
        assertThat(st04.isDone()).isFalse();

        release.countDown();
        marking.get(10, TimeUnit.SECONDS);

        assertThat(st04.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.NOT_SENT);
        assertThat(fx.outbox(pet, "VACCINE_REMINDER", "VACCINE_REMINDER_APP")).isEmpty();
        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(remindedAt(dose)).isNotNull();
    }

    /**
     * ST04 giữ thú trước → bên đánh dấu mất chờ tới khi ST04 commit, rồi mới đánh dấu. Task {@code VACCINE_DUE} ST04 vừa sinh
     * còn {@code OPEN} (ST19 hủy task khi thú mất chưa làm — T39 phần sau), nhưng không bao giờ có task sinh SAU khi thú đã
     * đánh dấu mất.
     */
    @Test
    void markingAPetDeceasedWaitsForSt04ThatAlreadyHoldsThePet() throws Exception {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.plusDays(3));
        DueDateWindow window = service.window();
        installPause(dose);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, 1)::text", String.class, PAUSE_NAMESPACE);
            held.countDown();
            await(release);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Future<ReminderResult> st04 = pool.submit(() -> service.remind(dose, window));
        awaitWaiting(WAITING_ST04_FLAG);
        Future<?> marking = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet);
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
        }));
        awaitWaiting(WAITING_PET_UPDATE);
        assertThat(st04.isDone()).isFalse();
        assertThat(marking.isDone()).isFalse();

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(st04.get(30, TimeUnit.SECONDS)).isEqualTo(ReminderResult.TASK_CREATED);
        marking.get(30, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", java.sql.Date.class, pet))
                .isNotNull();
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
    }

    // ================================================================ helpers

    /** Trigger tạm: UPDATE đặt {@code due_reminded_at} của mũi {@code dose} chờ advisory {@value #PAUSE_NAMESPACE}. */
    private void installPause(long dose) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION vaccine_race_it_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(%d, 1); RETURN NEW; END $$""".formatted(PAUSE_NAMESPACE));
        jdbc.execute("CREATE TRIGGER vaccine_race_it_pause BEFORE UPDATE ON vaccinations FOR EACH ROW WHEN "
                + "(NEW.id = " + dose + " AND OLD.due_reminded_at IS NULL AND NEW.due_reminded_at IS NOT NULL) "
                + "EXECUTE FUNCTION vaccine_race_it_pause()");
    }

    private Instant remindedAt(long dose) {
        Timestamp at = jdbc.queryForObject("SELECT due_reminded_at FROM vaccinations WHERE id = ?", Timestamp.class, dose);
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
