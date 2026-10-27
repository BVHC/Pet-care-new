package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.visit.job.VaccineOverdueJob;
import com.petcare.module.visit.job.VaccineReminderJob;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineOverdueService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * ST18 quá hạn tái chủng (T39 phần 5, BR-TB-04, docs/adr/0083) trên Postgres 17 thật, với bean thật của customer / care.
 * Đồng hồ {@link MutableClock} ở <b>năm 1990</b>: câu quét không có cận dưới nên mọi dữ liệu của test phải nằm trước mốc
 * của IT khác (era ≥ 2000) để job không chạm dữ liệu của IT khác. Chứng minh: dòng nào được xét, người nhận / chi nhánh /
 * chủ của task; chạy trùng (hai lượt song song, id quét đã cũ, chạy lại, [CFG] đổi giữa hai lượt, ST04 cùng mũi, mũi
 * khôi phục, task đã có sẵn, tồn đọng); lỗi trước / lúc commit rollback cả cờ; mũi lỗi vĩnh viễn không chặn mũi sau.
 * Ca tranh chấp với luồng ghi mũi / thú mất ở {@link VaccineOverdueRaceIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccineOverdueIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class VaccineOverdueIT {

    static final LocalDate TODAY = LocalDate.of(1990, 6, 1);
    /** 01/06/1990 10:00 giờ Việt Nam. */
    static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    /** N = [CFG] {@code reminder.vaccine_overdue_days} (V2). */
    private static final int N = 7;
    private static final Pattern CREATED = Pattern.compile("VACCINE_OVERDUE created=(\\d+)");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private VaccineOverdueJob job;
    @Autowired
    private VaccineOverdueService service;
    @Autowired
    private VaccineReminderJob reminderJob;
    @Autowired
    private SystemConfigService configs;

    private ReminderItFixture fx;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        fx = new ReminderItFixture(jdbc);
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_overdue_it_fail ON care_tasks");
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_overdue_it_fail_flag ON vaccinations");
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_overdue_it_fail_commit ON care_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS vaccine_overdue_it_fail()");
        try {
            assertThat(invariantViolations()).as("cờ ⇒ đúng một task (thú sống, có SĐT)").isEmpty();
        } finally {
            fx.cleanUp();
        }
    }

    // ================================================================ dòng nào được xét

    /** Task ở chi nhánh của mũi, chủ hiện tại, ngày đến hạn gốc; chỉ hai cột cờ của mũi đổi. */
    @Test
    void overdueDoseGetsOneTaskForTheDoseBranchAndOnlyTheFlagColumnsChange() {
        long pet = fx.pet(fx.silent);
        LocalDate due = TODAY.minusDays(10);
        long dose = fx.dose(fx.completedVisit(fx.otherBranch, fx.silent, pet, T0), due);
        String before = rowWithoutFlag(dose);

        job.run();

        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("task_type", "VACCINE_OVERDUE").containsEntry("status", "OPEN")
                    .containsEntry("branch_id", fx.otherBranch).containsEntry("customer_id", fx.silent)
                    .containsEntry("vaccination_id", dose);
            assertThat(task.get("due_date").toString()).isEqualTo(due.toString());
        });
        assertThat(rowWithoutFlag(dose)).isEqualTo(before);
        Map<String, Object> marks = jdbc.queryForMap("SELECT overdue_handled_at, updated_at FROM vaccinations WHERE id = ?",
                dose);
        assertThat(((Timestamp) marks.get("overdue_handled_at")).toInstant()).isEqualTo(T0);
        assertThat(marks.get("updated_at")).isEqualTo(marks.get("overdue_handled_at"));
        assertThat(dueRemindedAt(dose)).as("cờ của ST04 không bị chạm").isNull();
    }

    /** Hôm nay − N và sớm hơn được xét; hôm nay − N + 1 thì chưa (cận trên tính). */
    @Test
    void cutoffBoundaryIsInclusive() {
        long before = fx.pet(fx.silent);
        long on = fx.pet(fx.silent);
        long after = fx.pet(fx.silent);
        fx.dose(fx.completedVisit(fx.branch, fx.silent, before, T0), TODAY.minusDays(N + 1));
        fx.dose(fx.completedVisit(fx.branch, fx.silent, on, T0), TODAY.minusDays(N));
        long notYet = fx.dose(fx.completedVisit(fx.branch, fx.silent, after, T0), TODAY.minusDays(N - 1));

        job.run();

        assertThat(fx.tasks(before)).hasSize(1);
        assertThat(fx.tasks(on)).hasSize(1);
        assertThat(fx.tasks(after)).isEmpty();
        assertThat(overdueHandledAt(notYet)).isNull();
    }

    /** Mũi đã thay thế, đã xét, hoặc của lượt chưa hoàn tất thì không được xét. */
    @Test
    void onlyUnsupersededUnhandledDosesOfCompletedVisitsAreRaised() {
        LocalDate day = TODAY.minusDays(20);
        long petOk = fx.pet(fx.silent);
        long petSuperseded = fx.pet(fx.silent);
        // Hồ sơ online không SĐT: cờ đặt tay không có task nên phải nằm ngoài bất biến "cờ ⇒ task" của tearDown.
        long petHandled = fx.pet(fx.online);
        long petOpen = fx.pet(fx.silent);
        fx.dose(fx.completedVisit(fx.branch, fx.silent, petOk, T0), day);
        long superseded = fx.dose(fx.completedVisit(fx.branch, fx.silent, petSuperseded, T0), day);
        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), superseded);
        long handled = fx.dose(fx.completedVisit(fx.branch, fx.online, petHandled, T0), day);
        jdbc.update("UPDATE vaccinations SET overdue_handled_at = ? WHERE id = ?", Timestamp.from(T0), handled);
        fx.dose(fx.visit(fx.branch, fx.silent, petOpen, "IN_PROGRESS", T0), day);

        job.run();

        assertThat(fx.tasks(petOk)).hasSize(1);
        assertThat(fx.tasks(petSuperseded)).isEmpty();
        assertThat(fx.tasks(petHandled)).isEmpty();
        assertThat(fx.tasks(petOpen)).isEmpty();
    }

    /**
     * Keyset lô sau ({@code findOverdueIdsAfter}, job chỉ gọi khi lô đủ {@code batch-size}): trang 2 mũi, hai mũi cùng ngày
     * nằm ở hai trang → đủ mọi mũi, đúng thứ tự {@code (next_due_date, id)}, không trùng.
     */
    @Test
    void keysetPagesCoverEveryDoseOnceInDueDateThenIdOrder() {
        List<Long> expected = new ArrayList<>();
        // id tăng theo thứ tự chèn nên hai mũi cùng ngày (30) đã đúng thứ tự (next_due_date, id); trang: [40, 30] [30, 20] [10].
        for (int days : new int[] {40, 30, 30, 20, 10}) {
            long pet = fx.pet(fx.silent);
            expected.add(fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(days)));
        }
        var cutoff = service.cutoff();

        List<Long> seen = new ArrayList<>();
        long lastId = 0;
        List<Long> page;
        do {
            page = service.findOverdueIds(cutoff, lastId, 2);
            seen.addAll(page);
            if (!page.isEmpty()) {
                lastId = page.get(page.size() - 1);
            }
        } while (page.size() == 2);

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen.stream().filter(expected::contains).toList()).containsExactlyElementsOf(expected);
    }

    /** BR-TB-04 chỉ loại hồ sơ không SĐT: tài khoản, hồ sơ quầy có email và hồ sơ quầy không email đều có task. */
    @Test
    void everyKindOfRecipientWithAPhoneGetsATaskRegardlessOfEmail() {
        jdbc.update("UPDATE customers SET phone = '0987654321' WHERE id = ?", fx.online);
        LocalDate due = TODAY.minusDays(9);
        Map<Long, Long> petOf = new HashMap<>();
        for (long customer : List.of(fx.online, fx.counter, fx.silent)) {
            long pet = fx.pet(customer);
            petOf.put(customer, pet);
            fx.dose(fx.completedVisit(fx.branch, customer, pet, T0), due);
        }

        job.run();

        petOf.forEach((customer, pet) -> assertThat(fx.tasks(pet)).as("khách %d", customer).singleElement()
                .satisfies(task -> assertThat(task).containsEntry("customer_id", customer)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE payload->>'ten_thu_cung' IN (?, ?, ?)",
                Long.class, fx.petName(petOf.get(fx.online)), fx.petName(petOf.get(fx.counter)),
                fx.petName(petOf.get(fx.silent)))).as("ST18 không gửi thông báo").isZero();
    }

    /** Hồ sơ không SĐT: cờ đặt, không task, không xét lại kể cả khi sau đó hồ sơ thêm SĐT (S2). */
    @Test
    void profileWithoutPhoneIsMarkedButGetsNoTaskAndIsNeverReconsidered() {
        long pet = fx.pet(fx.online);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.online, pet, T0), TODAY.minusDays(10));

        job.run();

        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(overdueHandledAt(dose)).isNotNull();
        jdbc.update("UPDATE customers SET phone = '0987654321' WHERE id = ?", fx.online);
        clock.advance(Duration.ofHours(1));
        job.run();
        assertThat(fx.tasks(pet)).isEmpty();
        // Trả hồ sơ về không SĐT: cờ đã đặt mà không task chỉ đúng khi hồ sơ không SĐT (bất biến ở tearDown).
        jdbc.update("UPDATE customers SET phone = NULL WHERE id = ?", fx.online);
    }

    @Test
    void deceasedPetIsMarkedButGetsNoTask() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);

        job.run();

        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(overdueHandledAt(dose)).isNotNull();
    }

    /** Thú đã chuyển chủ sau lượt tiêm: task gọi chủ hiện tại, chi nhánh vẫn là chi nhánh của mũi (BR-KH-08, BR-TB-02). */
    @Test
    void taskGoesToTheCurrentOwnerOfThePet() {
        long pet = fx.pet(fx.silent);
        fx.dose(fx.completedVisit(fx.otherBranch, fx.silent, pet, T0), TODAY.minusDays(10));
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", fx.counter, pet);

        job.run();

        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("customer_id", fx.counter).containsEntry("branch_id", fx.otherBranch));
    }

    // ================================================================ R — chạy trùng

    /** R2: hai lượt song song → mỗi mũi đúng một task, tổng `created` đúng, không log lỗi. */
    @Test
    void twoConcurrentRunsProcessEachDoseExactlyOnce(CapturedOutput output) throws Exception {
        int expectedTasks = 0;
        for (int i = 0; i < 30; i++) {
            long customer = i % 3 == 0 ? fx.counter : fx.silent;
            long pet = fx.pet(customer);
            fx.dose(fx.completedVisit(i % 2 == 0 ? fx.branch : fx.otherBranch, customer, pet, T0),
                    TODAY.minusDays(N + i % 9));
            if (i % 5 == 4) {
                jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            } else {
                expectedTasks++;
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    job.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fx.taskCount()).isEqualTo(expectedTasks);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT vaccination_id FROM care_tasks WHERE task_type = 'VACCINE_OVERDUE'
                  AND branch_id IN (?, ?) GROUP BY vaccination_id HAVING count(*) > 1) d""", Long.class, fx.branch,
                fx.otherBranch)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vaccinations WHERE branch_id IN (?, ?) AND overdue_handled_at IS NULL""",
                Long.class, fx.branch, fx.otherBranch)).isZero();
        assertThat(createdIn(output)).isEqualTo(expectedTasks);
        assertThat(output).doesNotContain("VACCINE_OVERDUE_FAILED");
    }

    /** R3: id đã quét nhưng đã được xử lý xong trước khi khóa → SKIPPED, không ghi gì. */
    @Test
    void aDoseHandledAfterTheScanIsSkippedWithoutWriting() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        var cutoff = service.cutoff();
        assertThat(service.findOverdueIds(cutoff, 0, 10)).contains(dose);

        assertThat(service.raise(dose, cutoff)).isEqualTo(ReminderResult.TASK_CREATED);
        String doseVersion = xmin("vaccinations", dose);
        String taskVersion = jdbc.queryForObject("SELECT xmin::text FROM care_tasks WHERE vaccination_id = ?", String.class,
                dose);

        assertThat(service.raise(dose, cutoff)).isEqualTo(ReminderResult.SKIPPED);
        assertThat(xmin("vaccinations", dose)).isEqualTo(doseVersion);
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM care_tasks WHERE vaccination_id = ?", String.class, dose))
                .isEqualTo(taskVersion);
        assertThat(service.raise(Long.MAX_VALUE, cutoff)).as("mũi không tồn tại").isEqualTo(ReminderResult.SKIPPED);
    }

    /** R4: chạy lại sau một giờ và sau một ngày không sinh thêm, không ghi lại dòng nào (xmin không đổi). */
    @Test
    void rerunCreatesNothingAndWritesNothing(CapturedOutput output) {
        List<Long> doses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long pet = fx.pet(fx.silent);
            doses.add(fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10 + i)));
        }
        long deceased = fx.pet(fx.silent);
        doses.add(fx.dose(fx.completedVisit(fx.branch, fx.silent, deceased, T0), TODAY.minusDays(12)));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), deceased);
        job.run();
        long tasksAfterFirst = fx.taskCount();
        Map<Long, String> versions = new HashMap<>();
        doses.forEach(d -> versions.put(d, xmin("vaccinations", d)));
        String tasksVersion = tasksXmins();

        clock.advance(Duration.ofHours(1));
        job.run();
        clock.advance(Duration.ofDays(1));
        job.run();

        assertThat(tasksAfterFirst).isEqualTo(3);
        assertThat(fx.taskCount()).isEqualTo(3);
        doses.forEach(d -> assertThat(xmin("vaccinations", d)).as("mũi %d", d).isEqualTo(versions.get(d)));
        assertThat(tasksXmins()).isEqualTo(tasksVersion);
        assertThat(output).contains("VACCINE_OVERDUE created=0 notCreated=0 skipped=0");
    }

    /** R7: ST04 (task nhắc, `next_due − 7`) rồi ST18 (task quá hạn, `next_due + 7`) cùng một mũi → mỗi loại đúng một. */
    @Test
    void sameDoseRemindedThenRaisedOverdueGivesOneOfEachType() {
        LocalDate due = TODAY.minusDays(7);
        long pet = fx.pet(fx.silent);
        fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), due);
        clock.set(ZonedDateTime.of(due.minusDays(7), LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant());
        reminderJob.run();
        assertThat(fx.tasks(pet)).extracting(t -> t.get("task_type")).containsExactly("VACCINE_DUE");

        clock.set(T0);
        job.run();
        job.run();
        reminderJob.run();

        assertThat(fx.tasks(pet)).extracting(t -> t.get("task_type")).containsExactlyInAnyOrder("VACCINE_DUE",
                "VACCINE_OVERDUE");
    }

    /** R8: hai loại vaccine của cùng thú cùng quá hạn → hai task; mũi mới (đã tiêm lại) về sau quá hạn lại → task riêng. */
    @Test
    void twoVaccineTypesOfOnePetGiveTwoTasksAndANewerDoseGetsItsOwn() {
        long otherType = fx.vaccines.vaccineType("DOG", "Care ST18");
        long otherProtocol = fx.vaccines.protocol("DOG", otherType, 1, 365, false);
        long pet = fx.pet(fx.counter);
        long visit = fx.completedVisit(fx.branch, fx.counter, pet, T0);
        long first = fx.dose(visit, TODAY.minusDays(10));
        fx.dose(visit, otherType, otherProtocol, TODAY.minusDays(10));

        job.run();
        assertThat(fx.tasks(pet)).hasSize(2);

        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), first);
        jdbc.update("UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = 'Đã tiêm lại (BR-TB-03)', "
                + "handled_at = ? WHERE vaccination_id = ?", Timestamp.from(T0), first);
        long newer = fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.minusDays(8));
        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.tasks(pet)).hasSize(3).extracting(t -> t.get("vaccination_id")).contains(newer);
    }

    /** R9 ([CFG] đổi, hai chiều): áp từ lượt kế tiếp; mũi đã xét không bị xét lại, không lúc nào có hai task. */
    @Test
    void configChangeAppliesFromTheNextRunAndNeverRaisesTwice() {
        long petB = fx.pet(fx.silent);
        long petA = fx.pet(fx.silent);
        long petC = fx.pet(fx.silent);
        long petD = fx.pet(fx.silent);
        long b = fx.dose(fx.completedVisit(fx.branch, fx.silent, petB, T0), TODAY.minusDays(8));
        long a = fx.dose(fx.completedVisit(fx.branch, fx.silent, petA, T0), TODAY.minusDays(5));
        long c = fx.dose(fx.completedVisit(fx.branch, fx.silent, petC, T0), TODAY.minusDays(12));
        String key = "reminder.vaccine_overdue_days";
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, key);
        try {
            job.run();
            assertThat(overdueHandledAt(b)).isNotNull();
            assertThat(overdueHandledAt(c)).isNotNull();
            assertThat(overdueHandledAt(a)).as("chưa quá 7 ngày").isNull();

            setConfig(key, "3");
            clock.advance(Duration.ofHours(1));
            job.run();
            assertThat(overdueHandledAt(a)).as("N giảm: áp từ lượt sau").isNotNull();

            setConfig(key, "14");
            long d = fx.dose(fx.completedVisit(fx.branch, fx.silent, petD, T0), TODAY.minusDays(10));
            clock.advance(Duration.ofHours(1));
            job.run();
            assertThat(overdueHandledAt(d)).as("N tăng: chưa quá 14 ngày").isNull();
            assertThat(fx.tasks(petB)).hasSize(1);
            assertThat(fx.tasks(petC)).hasSize(1);
            assertThat(fx.tasks(petA)).hasSize(1);

            setConfig(key, original);
            clock.advance(Duration.ofHours(1));
            job.run();
            assertThat(fx.tasks(petD)).hasSize(1);
        } finally {
            setConfig(key, original);
        }
    }

    /** R10: mũi đã xét rồi bị thay thế và khôi phục → không xét lại; task đã hủy không mở lại. */
    @Test
    void aRestoredDoseAlreadyHandledIsNotRaisedAgain() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        job.run();
        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), dose);
        jdbc.update("UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = 'Đã tiêm lại (BR-TB-03)', "
                + "handled_at = ? WHERE vaccination_id = ?", Timestamp.from(T0), dose);
        jdbc.update("UPDATE vaccinations SET superseded_at = NULL WHERE id = ?", dose);

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "CANCELLED"));
    }

    /** R10 (chưa xét): mũi bị thay thế rồi khôi phục khi chưa có cờ → xét bình thường, đúng một task. */
    @Test
    void aRestoredUnhandledDoseIsRaisedOnce() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), dose);
        job.run();
        assertThat(fx.tasks(pet)).isEmpty();
        jdbc.update("UPDATE vaccinations SET superseded_at = NULL WHERE id = ?", dose);

        job.run();
        job.run();

        assertThat(fx.tasks(pet)).hasSize(1);
    }

    /** R11: cờ NULL nhưng task quá hạn đã có (đã hủy) → không dòng thứ hai, trạng thái cũ giữ nguyên, cờ được đặt. */
    @Test
    void existingOverdueTaskIsReusedAndNoSecondRowAppears() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        jdbc.update("""
                INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, vaccination_id, due_date, status,
                                        cancel_reason, handled_at, created_at, updated_at)
                VALUES (?, ?, ?, 'VACCINE_OVERDUE', ?, ?, 'CANCELLED', 'Khách đã chuyển đi', ?, ?, ?)""", fx.branch,
                fx.silent, pet, dose, TODAY.minusDays(10), Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0));

        job.run();

        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "CANCELLED"));
        assertThat(overdueHandledAt(dose)).isNotNull();
    }

    /** R13: job tắt lâu rồi bật lại → mỗi mũi quá hạn (7, 30, 400 ngày) đúng một task. */
    @Test
    void backlogAfterDowntimeGetsOneTaskPerDose() {
        List<Long> pets = new ArrayList<>();
        for (int days : new int[] {N, 30, 400}) {
            long pet = fx.pet(fx.silent);
            pets.add(pet);
            fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(days));
        }

        job.run();
        job.run();

        pets.forEach(pet -> assertThat(fx.tasks(pet)).hasSize(1));
    }

    // ================================================================ RT — lỗi giữa chừng

    /** F1: INSERT task bị từ chối → rollback cả cờ; hết lỗi thì lượt sau sinh đúng một task. */
    @Test
    void taskFailureRollsBackTheFlagAndTheNextRunSucceeds(CapturedOutput output) {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        failOn("INSERT", "care_tasks", "vaccine_overdue_it_fail", "NEW.task_type = 'VACCINE_OVERDUE'");

        job.run();

        assertThat(output).contains("VACCINE_OVERDUE_FAILED vaccinationId=" + dose);
        assertThat(overdueHandledAt(dose)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
        jdbc.execute("DROP TRIGGER vaccine_overdue_it_fail ON care_tasks");

        job.run();

        assertThat(overdueHandledAt(dose)).isNotNull();
        assertThat(fx.tasks(pet)).hasSize(1);
    }

    /** F2: UPDATE cờ bị từ chối → không cờ, không task. */
    @Test
    void flagFailureWritesNothing(CapturedOutput output) {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        failOn("UPDATE", "vaccinations", "vaccine_overdue_it_fail_flag",
                "NEW.id = " + dose + " AND NEW.overdue_handled_at IS NOT NULL");

        job.run();

        assertThat(output).contains("VACCINE_OVERDUE_FAILED vaccinationId=" + dose);
        assertThat(overdueHandledAt(dose)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
    }

    /** RT1: lỗi nổ lúc COMMIT (constraint trigger hoãn) → cờ và task cùng mất; lượt sau sinh đúng một task. */
    @Test
    void commitFailureLeavesNothingAndNextRunRaisesOnce(CapturedOutput output) {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.minusDays(10));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION vaccine_overdue_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'IT rejects commit' USING ERRCODE = '23514'; END $$""");
        jdbc.execute("CREATE CONSTRAINT TRIGGER vaccine_overdue_it_fail_commit AFTER INSERT ON care_tasks "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.task_type = 'VACCINE_OVERDUE') "
                + "EXECUTE FUNCTION vaccine_overdue_it_fail()");

        job.run();

        assertThat(output).contains("VACCINE_OVERDUE_FAILED vaccinationId=" + dose);
        assertThat(overdueHandledAt(dose)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
        jdbc.execute("DROP TRIGGER vaccine_overdue_it_fail_commit ON care_tasks");

        job.run();

        assertThat(fx.tasks(pet)).hasSize(1);
        assertThat(overdueHandledAt(dose)).isNotNull();
    }

    /** RT4: mũi lỗi vĩnh viễn đứng đầu keyset không chặn mũi sau; hết lỗi thì lượt sau xử lý nốt. */
    @Test
    void aPermanentlyFailingDoseDoesNotBlockLaterDoses(CapturedOutput output) {
        long petBad = fx.pet(fx.silent);
        long petGood = fx.pet(fx.silent);
        long bad = fx.dose(fx.completedVisit(fx.branch, fx.silent, petBad, T0), TODAY.minusDays(30));
        long good = fx.dose(fx.completedVisit(fx.branch, fx.silent, petGood, T0), TODAY.minusDays(10));
        failOn("INSERT", "care_tasks", "vaccine_overdue_it_fail", "NEW.vaccination_id = " + bad);

        job.run();

        assertThat(output).contains("VACCINE_OVERDUE_FAILED vaccinationId=" + bad);
        assertThat(fx.tasks(petGood)).hasSize(1);
        assertThat(overdueHandledAt(good)).isNotNull();
        assertThat(overdueHandledAt(bad)).isNull();
        jdbc.execute("DROP TRIGGER vaccine_overdue_it_fail ON care_tasks");

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.tasks(petBad)).hasSize(1);
        assertThat(fx.tasks(petGood)).hasSize(1);
    }

    // ================================================================ helpers

    private void failOn(String event, String table, String trigger, String condition) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION vaccine_overdue_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$""");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE " + event + " ON " + table + " FOR EACH ROW WHEN (" + condition
                + ") EXECUTE FUNCTION vaccine_overdue_it_fail()");
    }

    private void setConfig(String key, String value) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", value, key);
        configs.reload();
    }

    private Instant overdueHandledAt(long dose) {
        Timestamp at = jdbc.queryForObject("SELECT overdue_handled_at FROM vaccinations WHERE id = ?", Timestamp.class,
                dose);
        return at == null ? null : at.toInstant();
    }

    private Instant dueRemindedAt(long dose) {
        Timestamp at = jdbc.queryForObject("SELECT due_reminded_at FROM vaccinations WHERE id = ?", Timestamp.class, dose);
        return at == null ? null : at.toInstant();
    }

    private String rowWithoutFlag(long dose) {
        return jdbc.queryForObject("SELECT (to_jsonb(v) - 'overdue_handled_at' - 'updated_at')::text FROM vaccinations v "
                + "WHERE id = ?", String.class, dose);
    }

    private String xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM " + table + " WHERE id = ?", String.class, id);
    }

    /** Phiên bản mọi task của test (id, xmin) theo thứ tự id, để chứng minh chạy lại không ghi dòng nào. */
    private String tasksXmins() {
        return jdbc.queryForList("SELECT id || ':' || xmin::text FROM care_tasks WHERE branch_id IN (?, ?) ORDER BY id",
                String.class, fx.branch, fx.otherBranch).toString();
    }

    /** Tổng số `created=` của mọi dòng tổng kết ST18 trong log. */
    private static int createdIn(CapturedOutput output) {
        Matcher matcher = CREATED.matcher(output.getAll());
        int total = 0;
        while (matcher.find()) {
            total += Integer.parseInt(matcher.group(1));
        }
        return total;
    }

    /**
     * Bất biến cờ ⇒ task (RT, mục C): mũi đã đặt cờ, của thú còn sống, chủ hiện tại có SĐT thì phải có đúng một task
     * {@code VACCINE_OVERDUE}. Trả id mũi vi phạm.
     */
    private List<Long> invariantViolations() {
        return jdbc.queryForList("""
                SELECT v.id FROM vaccinations v JOIN pets p ON p.id = v.pet_id JOIN customers c ON c.id = p.customer_id
                WHERE v.branch_id IN (?, ?) AND v.overdue_handled_at IS NOT NULL AND p.deceased_on IS NULL
                  AND c.phone IS NOT NULL
                  AND (SELECT count(*) FROM care_tasks t WHERE t.vaccination_id = v.id
                       AND t.task_type = 'VACCINE_OVERDUE') <> 1""", Long.class, fx.branch, fx.otherBranch);
    }
}
