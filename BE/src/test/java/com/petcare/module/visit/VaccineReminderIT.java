package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
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

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.care.job.NotificationOutboxJob;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.visit.job.VaccineReminderJob;
import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineReminderService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

/**
 * ST04 nhắc tái chủng (T39 phần 3, BR-TB-01, BR-TB-02, docs/adr/0082) trên Postgres 17 thật, với bean thật của customer /
 * catalog / branch / identity / care (worker ST20 + GreenMail). Đồng hồ {@link MutableClock} ở <b>năm 2021</b> (mọi ngày
 * đến hạn của test quanh 01/06/2021 nên cửa sổ quét không đụng dữ liệu của IT khác). Chứng minh: dòng nào được nhắc, người
 * nhận theo BR-TB-02, chủ hiện tại, thú đã mất; chạy trùng (hai lượt song song, chạy lại, [CFG] đổi giữa hai lượt, nhắc
 * hai loại cùng ngày, mũi được khôi phục); lỗi sau khi đặt cờ rollback cả cờ; mẫu V22 render được từ đầu tới cuối qua ST20.
 * Ca tranh chấp với luồng ghi mũi / thú mất ở {@link VaccineReminderRaceIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VaccineReminderIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class VaccineReminderIT {

    private static final LocalDate TODAY = LocalDate.of(2021, 6, 1);
    /** 01/06/2021 10:00 giờ Việt Nam. */
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final String[] CODES = {"VACCINE_REMINDER", "VACCINE_REMINDER_APP"};

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@example.com", "test-app-password"))
            .withPerMethodLifecycle(true);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private VaccineReminderJob job;
    @Autowired
    private VaccineReminderService service;
    @Autowired
    private InAppNotificationJob inAppJob;
    @Autowired
    private NotificationOutboxJob emailJob;
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
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_reminder_it_fail ON notification_outbox");
        jdbc.execute("DROP TRIGGER IF EXISTS vaccine_reminder_it_fail_task ON care_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS vaccine_reminder_it_fail()");
        fx.cleanUp();
    }

    // ================================================================ R — dòng nào được nhắc

    /** Tài khoản đã xác thực: một bộ IN_APP (kèm link điền sẵn) + EMAIL tới email tài khoản; chỉ hai cột cờ đổi. */
    @Test
    void dueDoseOfAnAccountIsRemindedOnceAndOnlyTheFlagColumnsChange() {
        long pet = fx.pet(fx.online);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.online, pet, T0), TODAY.plusDays(7));
        String before = rowWithoutFlag(dose);

        job.run();

        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("template_code"), r -> r.get("channel"),
                r -> r.get("recipient_account_id"), r -> r.get("recipient_email"), r -> r.get("link_url"))
                .containsExactlyInAnyOrder(
                        tuple("VACCINE_REMINDER_APP", "IN_APP", fx.account, null,
                                "/booking?petId=%d&branchId=%d&serviceType=VACCINE".formatted(pet, fx.branch)),
                        tuple("VACCINE_REMINDER", "EMAIL", null, fx.accountEmail, null));
        Map<String, Object> payload = payloadOf(pet, "VACCINE_REMINDER");
        assertThat(payload).containsEntry("ten_khach", "Trần Thị Online").containsEntry("ten_thu_cung", fx.petName(pet))
                .containsEntry("loai_vaccine", typeName(fx.type)).containsEntry("ngay_tai_chung", "08/06/2021")
                .containsEntry("chi_nhanh", branchName(fx.branch)).containsEntry("so_dien_thoai_chi_nhanh", "0280000000");
        assertThat(rowWithoutFlag(dose)).isEqualTo(before);
        Map<String, Object> marks = jdbc.queryForMap("SELECT due_reminded_at, updated_at FROM vaccinations WHERE id = ?",
                dose);
        assertThat(((Timestamp) marks.get("due_reminded_at")).toInstant()).isEqualTo(T0);
        assertThat(marks.get("updated_at")).isEqualTo(marks.get("due_reminded_at"));
        assertThat(fx.tasks(pet)).isEmpty();
    }

    /** Hôm nay và hôm nay + 7 được nhắc; hôm qua và hôm nay + 8 thì không (cửa sổ [CFG] 7 ngày, cả hai cận tính). */
    @Test
    void windowBoundsAreInclusiveAndOutsideIsIgnored() {
        Map<Integer, Long> pets = new HashMap<>();
        Map<Integer, Long> doses = new HashMap<>();
        for (int offset : List.of(-1, 0, 7, 8)) {
            long pet = fx.pet(fx.counter);
            pets.put(offset, pet);
            doses.put(offset, fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.plusDays(offset)));
        }

        assertThat(service.findDueIds(service.window(), 0, 1000)).contains(doses.get(0), doses.get(7))
                .doesNotContain(doses.get(-1), doses.get(8));

        job.run();

        assertThat(fx.outbox(pets.get(-1), CODES)).isEmpty();
        assertThat(fx.outbox(pets.get(0), CODES)).hasSize(1);
        assertThat(fx.outbox(pets.get(7), CODES)).hasSize(1);
        assertThat(fx.outbox(pets.get(8), CODES)).isEmpty();
        assertThat(remindedAt(doses.get(-1))).isNull();
        assertThat(remindedAt(doses.get(0))).isNotNull();
        assertThat(remindedAt(doses.get(8))).isNull();
    }

    /** Chỉ mũi chưa bị thay thế, chưa nhắc, của lượt COMPLETED (nghĩa vụ ADR-0075). */
    @Test
    void onlyUnsupersededUnremindedDosesOfCompletedVisitsAreReminded() {
        long inProgress = fx.pet(fx.counter);
        long inProgressDose = fx.dose(fx.visit(fx.branch, fx.counter, inProgress, "IN_PROGRESS", T0), TODAY.plusDays(3));
        long superseded = fx.pet(fx.counter);
        long supersededDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, superseded, T0), TODAY.plusDays(3));
        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), supersededDose);
        long reminded = fx.pet(fx.counter);
        long remindedDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, reminded, T0), TODAY.plusDays(3));
        jdbc.update("UPDATE vaccinations SET due_reminded_at = ? WHERE id = ?", Timestamp.from(T0), remindedDose);
        long due = fx.pet(fx.counter);
        long dueDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, due, T0), TODAY.plusDays(3));

        job.run();

        assertThat(fx.outbox(inProgress, CODES)).isEmpty();
        assertThat(fx.outbox(superseded, CODES)).isEmpty();
        assertThat(fx.outbox(reminded, CODES)).isEmpty();
        assertThat(fx.outbox(due, CODES)).hasSize(1);
        assertThat(remindedAt(inProgressDose)).isNull();
        assertThat(remindedAt(supersededDose)).isNull();
        assertThat(remindedAt(dueDose)).isNotNull();
        assertThat(service.findDueIds(service.window(), 0, 1000)).doesNotContain(inProgressDose, supersededDose,
                remindedDose);
    }

    // ================================================================ BR-TB-02 — kênh

    @Test
    void counterProfileWithEmailGetsOnlyAnEmail() {
        long pet = fx.pet(fx.counter);
        fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.plusDays(5));

        job.run();

        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("template_code"), r -> r.get("channel"),
                r -> r.get("recipient_email"), r -> r.get("link_url"))
                .containsExactly(tuple("VACCINE_REMINDER", "EMAIL", fx.counterEmail, null));
        assertThat(fx.tasks(pet)).isEmpty();
    }

    /** Hồ sơ không email: không outbox, đúng một Care Task VACCINE_DUE cho chi nhánh của chính mũi (không phải chi nhánh khác). */
    @Test
    void profileWithoutEmailGetsADueTaskForTheBranchOfTheDoseAndNothingElse() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.otherBranch, fx.silent, pet, T0), TODAY.plusDays(5));

        job.run();

        assertThat(fx.outbox(pet, CODES)).isEmpty();
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("task_type", "VACCINE_DUE").containsEntry("status", "OPEN")
                    .containsEntry("branch_id", fx.otherBranch).containsEntry("customer_id", fx.silent)
                    .containsEntry("vaccination_id", dose);
            assertThat(task.get("due_date").toString()).isEqualTo(TODAY.plusDays(5).toString());
        });
        assertThat(remindedAt(dose)).isNotNull();
        assertThat(invariantViolations()).isEmpty();
    }

    /** Thú đã mất (G3): đánh cờ để không quét lại, không gửi, không task. */
    @Test
    void deceasedPetIsMarkedButNothingIsSent() {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.plusDays(5));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(2), pet);

        job.run();

        assertThat(fx.outbox(pet, CODES)).isEmpty();
        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(remindedAt(dose)).isNotNull();
    }

    /** Nhắc đi theo thú sang chủ mới (BR-KH-08): kênh và Care Task tính theo chủ hiện tại, không theo chủ lúc tiêm. */
    @Test
    void reminderGoesToTheCurrentOwnerOfThePet() {
        long toEmail = fx.pet(fx.silent);
        fx.dose(fx.completedVisit(fx.branch, fx.silent, toEmail, T0), TODAY.plusDays(5));
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", fx.counter, toEmail);
        long toTask = fx.pet(fx.counter);
        long taskDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, toTask, T0), TODAY.plusDays(5));
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", fx.silent, toTask);

        job.run();

        assertThat(fx.outbox(toEmail, CODES)).extracting(r -> r.get("recipient_email")).containsExactly(fx.counterEmail);
        assertThat(fx.tasks(toEmail)).isEmpty();
        assertThat(fx.outbox(toTask, CODES)).isEmpty();
        assertThat(fx.tasks(toTask)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("customer_id", fx.silent).containsEntry("vaccination_id", taskDose));
    }

    // ================================================================ D — chạy trùng

    /** D2: hai lượt song song → mỗi mũi đúng một bộ đầu ra, không log lỗi. */
    @Test
    void twoConcurrentRunsProcessEachDoseExactlyOnce(CapturedOutput output) throws Exception {
        Map<Long, Integer> expectedOutbox = new HashMap<>();
        int silentCount = 0;
        for (int i = 0; i < 16; i++) {
            long customer = switch (i % 4) {
                case 0 -> fx.online;
                case 1 -> fx.counter;
                default -> fx.silent;
            };
            long pet = fx.pet(customer);
            fx.dose(fx.completedVisit(i % 2 == 0 ? fx.branch : fx.otherBranch, customer, pet, T0),
                    TODAY.plusDays(1 + i % 7));
            if (i % 4 == 3) {
                jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
                expectedOutbox.put(pet, 0);
            } else {
                expectedOutbox.put(pet, i % 4 == 0 ? 2 : i % 4 == 1 ? 1 : 0);
                silentCount += i % 4 == 2 ? 1 : 0;
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

        expectedOutbox.forEach((pet, rows) -> assertThat(fx.outbox(pet, CODES)).as("thú %d", pet).hasSize(rows));
        assertThat(fx.taskCount()).as("mỗi hồ sơ không email đúng một task").isEqualTo(silentCount);
        assertThat(invariantViolations()).isEmpty();
        assertThat(output).doesNotContain("VACCINE_REMINDER_FAILED");
    }

    /** D3: chạy lại sau một giờ không gửi thêm, không sinh thêm task, không ghi lại dòng nào (xmin không đổi). */
    @Test
    void rerunSendsNothingAndWritesNothing() {
        List<Long> doses = new ArrayList<>();
        List<Long> pets = new ArrayList<>();
        for (long customer : List.of(fx.online, fx.counter, fx.silent)) {
            long pet = fx.pet(customer);
            pets.add(pet);
            doses.add(fx.dose(fx.completedVisit(fx.branch, customer, pet, T0), TODAY.plusDays(4)));
        }
        long deceased = fx.pet(fx.silent);
        pets.add(deceased);
        doses.add(fx.dose(fx.completedVisit(fx.branch, fx.silent, deceased, T0), TODAY.plusDays(4)));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), deceased);
        job.run();
        long outboxAfterFirst = pets.stream().mapToLong(p -> fx.outbox(p, CODES).size()).sum();
        long tasksAfterFirst = fx.taskCount();
        Map<Long, String> versions = new HashMap<>();
        doses.forEach(d -> versions.put(d, xmin(d)));

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(outboxAfterFirst).isEqualTo(3);
        assertThat(pets.stream().mapToLong(p -> fx.outbox(p, CODES).size()).sum()).isEqualTo(outboxAfterFirst);
        assertThat(fx.taskCount()).isEqualTo(tasksAfterFirst).isEqualTo(1);
        doses.forEach(d -> assertThat(xmin(d)).as("mũi %d", d).isEqualTo(versions.get(d)));
    }

    /** D4: INSERT outbox lỗi → rollback cả cờ; hết lỗi thì lượt sau gửi đúng một bộ. */
    @Test
    void outboxFailureRollsBackTheFlagAndTheNextRunSucceeds(CapturedOutput output) {
        long pet = fx.pet(fx.online);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.online, pet, T0), TODAY.plusDays(2));
        failInsertsOn("notification_outbox", "vaccine_reminder_it_fail",
                "NEW.template_code IN ('VACCINE_REMINDER', 'VACCINE_REMINDER_APP')");

        job.run();

        assertThat(output).contains("VACCINE_REMINDER_FAILED vaccinationId=" + dose);
        assertThat(remindedAt(dose)).isNull();
        assertThat(fx.outbox(pet, CODES)).isEmpty();
        jdbc.execute("DROP TRIGGER vaccine_reminder_it_fail ON notification_outbox");

        job.run();

        assertThat(remindedAt(dose)).isNotNull();
        assertThat(fx.outbox(pet, CODES)).hasSize(2);
    }

    /** D4: INSERT Care Task lỗi → rollback cả cờ; hết lỗi thì lượt sau sinh đúng một task. */
    @Test
    void taskFailureRollsBackTheFlagAndTheNextRunSucceeds(CapturedOutput output) {
        long pet = fx.pet(fx.silent);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, T0), TODAY.plusDays(2));
        failInsertsOn("care_tasks", "vaccine_reminder_it_fail_task", "NEW.task_type = 'VACCINE_DUE'");

        job.run();

        assertThat(output).contains("VACCINE_REMINDER_FAILED vaccinationId=" + dose);
        assertThat(remindedAt(dose)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
        jdbc.execute("DROP TRIGGER vaccine_reminder_it_fail_task ON care_tasks");

        job.run();

        assertThat(remindedAt(dose)).isNotNull();
        assertThat(fx.tasks(pet)).hasSize(1);
    }

    /** ST20 chuyển dòng outbox sang FAILED sau 5 lần thử: ST04 không gửi lại (tại-nhiều-nhất-một-lần, ADR-0082). */
    @Test
    void failedDeliveryIsNotRemindedAgain() {
        long pet = fx.pet(fx.counter);
        fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.plusDays(2));
        job.run();
        jdbc.update("UPDATE notification_outbox SET status = 'FAILED' WHERE payload->>'ten_thu_cung' = ?",
                fx.petName(pet));

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.outbox(pet, CODES)).hasSize(1).extracting(r -> r.get("status")).containsExactly("FAILED");
    }

    /** D7: một thú đến hạn hai loại vaccine cùng ngày → hai nhắc riêng, không phải trùng. */
    @Test
    void twoVaccineTypesDueTheSameDayGiveTwoReminders() {
        long otherType = fx.vaccines.vaccineType("DOG", "Care ST04");
        long otherProtocol = fx.vaccines.protocol("DOG", otherType, 1, 365, false);
        long pet = fx.pet(fx.counter);
        long visit = fx.completedVisit(fx.branch, fx.counter, pet, T0);
        fx.dose(visit, TODAY.plusDays(3));
        fx.dose(visit, otherType, otherProtocol, TODAY.plusDays(3));

        job.run();

        assertThat(fx.outbox(pet, CODES)).hasSize(2);
        assertThat(fx.outbox(pet, CODES)).extracting(r -> payloadMap(r).get("loai_vaccine"))
                .containsExactlyInAnyOrder(typeName(fx.type), typeName(otherType));
    }

    /** D8: mũi đã nhắc, bị thay thế rồi được khôi phục (xóa mũi mới, ADR-0075) → không nhắc lại. */
    @Test
    void aRestoredDoseThatWasAlreadyRemindedIsNotRemindedAgain() {
        long pet = fx.pet(fx.counter);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, T0), TODAY.plusDays(3));
        job.run();
        assertThat(fx.outbox(pet, CODES)).hasSize(1);
        jdbc.update("UPDATE vaccinations SET superseded_at = ? WHERE id = ?", Timestamp.from(T0), dose);
        jdbc.update("UPDATE vaccinations SET superseded_at = NULL WHERE id = ?", dose);

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.outbox(pet, CODES)).hasSize(1);
    }

    /** D9 (BR-QT-13, ADR-0057): [CFG] đổi giữa hai lượt có hiệu lực từ lượt sau; mũi đã nhắc không bị nhắc lại. */
    @Test
    void configChangeAppliesFromTheNextRunAndNeverRemindsTwice() {
        long near = fx.pet(fx.counter);
        long nearDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, near, T0), TODAY.plusDays(2));
        long far = fx.pet(fx.counter);
        long farDose = fx.dose(fx.completedVisit(fx.branch, fx.counter, far, T0), TODAY.plusDays(6));
        String key = "reminder.vaccine_days_before";
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, key);
        try {
            jdbc.update("UPDATE system_configs SET value = '3' WHERE key = ?", key);
            configs.reload();
            job.run();
            assertThat(remindedAt(nearDose)).isNotNull();
            assertThat(remindedAt(farDose)).isNull();
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, key);
            configs.reload();
        }

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(remindedAt(farDose)).isNotNull();
        assertThat(fx.outbox(near, CODES)).hasSize(1);
        assertThat(fx.outbox(far, CODES)).hasSize(1);
    }

    // ================================================================ cửa sổ, kết quả từng bản ghi

    @Test
    void remindReturnsTheResultPerKindOfRecipient() {
        long a = fx.pet(fx.online);
        long b = fx.pet(fx.silent);
        long c = fx.pet(fx.counter);
        long da = fx.dose(fx.completedVisit(fx.branch, fx.online, a, T0), TODAY.plusDays(1));
        long db = fx.dose(fx.completedVisit(fx.branch, fx.silent, b, T0), TODAY.plusDays(1));
        long dc = fx.dose(fx.completedVisit(fx.branch, fx.counter, c, T0), TODAY.plusDays(1));
        DueDateWindow window = service.window();

        assertThat(service.remind(da, window)).isEqualTo(ReminderResult.REMINDED);
        assertThat(service.remind(db, window)).isEqualTo(ReminderResult.TASK_CREATED);
        assertThat(service.remind(dc, window)).isEqualTo(ReminderResult.REMINDED);
        assertThat(service.remind(da, window)).as("đã nhắc").isEqualTo(ReminderResult.SKIPPED);
        assertThat(service.remind(Long.MAX_VALUE, window)).as("mũi không tồn tại").isEqualTo(ReminderResult.SKIPPED);
    }

    // ================================================================ ST20 — mẫu V22 gửi được từ đầu tới cuối

    /** Chạy ST20 thật trên các dòng ST04 vừa ghi: SENT (không FAILED "thiếu biến"), thư và thông báo trong app đúng nội dung. */
    @Test
    void outboxRowsRenderWithTheV22TemplatesThroughSt20() throws Exception {
        long pet = fx.pet(fx.online);
        fx.dose(fx.completedVisit(fx.branch, fx.online, pet, T0), TODAY.plusDays(7));
        job.run();

        MimeMessage mail = awaitMail(fx.accountEmail);
        inAppJob.run();

        assertThat(mail.getSubject()).isEqualTo("Nhắc tiêm %s cho %s ngày 08/06/2021".formatted(typeName(fx.type),
                fx.petName(pet)));
        assertThat(mail.getContent().toString()).contains("Xin chào Trần Thị Online")
                .contains("tại " + branchName(fx.branch)).contains("0280000000");
        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("status")).containsOnly("SENT");
        assertThat(jdbc.queryForList("SELECT type, title, body, link_url FROM notifications WHERE account_id = ?",
                fx.account)).singleElement().satisfies(n -> {
                    assertThat(n).containsEntry("type", "VACCINE_REMINDER")
                            .containsEntry("link_url", "/booking?petId=%d&branchId=%d&serviceType=VACCINE"
                                    .formatted(pet, fx.branch));
                    assertThat(n.get("title").toString()).contains(fx.petName(pet));
                    assertThat(n.get("body").toString()).contains(typeName(fx.type)).contains("08/06/2021");
                });
    }

    // ================================================================ helpers

    private MimeMessage awaitMail(String recipient) throws Exception {
        for (int round = 0; round < 20; round++) {
            emailJob.run();
            for (MimeMessage message : SMTP.getReceivedMessages()) {
                for (var address : message.getRecipients(Message.RecipientType.TO)) {
                    if (recipient.equals(address.toString())) {
                        return message;
                    }
                }
            }
        }
        throw new AssertionError("không nhận được thư tới " + recipient);
    }

    private void failInsertsOn(String table, String trigger, String condition) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION vaccine_reminder_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$""");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table + " FOR EACH ROW WHEN (" + condition
                + ") EXECUTE FUNCTION vaccine_reminder_it_fail()");
    }

    private Instant remindedAt(long dose) {
        Timestamp at = jdbc.queryForObject("SELECT due_reminded_at FROM vaccinations WHERE id = ?", Timestamp.class, dose);
        return at == null ? null : at.toInstant();
    }

    private String rowWithoutFlag(long dose) {
        return jdbc.queryForObject("SELECT (to_jsonb(v) - 'due_reminded_at' - 'updated_at')::text FROM vaccinations v "
                + "WHERE id = ?", String.class, dose);
    }

    private String xmin(long dose) {
        return jdbc.queryForObject("SELECT xmin::text FROM vaccinations WHERE id = ?", String.class, dose);
    }

    private String typeName(long typeId) {
        return jdbc.queryForObject("SELECT name FROM vaccine_types WHERE id = ?", String.class, typeId);
    }

    private String branchName(long branchId) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branchId);
    }

    private Map<String, Object> payloadOf(long pet, String code) {
        return fx.outbox(pet, code).stream().findFirst().map(VaccineReminderIT::payloadMap).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadMap(Map<String, Object> row) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(row.get("payload").toString(), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Task của thú trong test phải thỏa các bất biến trạng thái ↔ cột xử lý (V22): trả id vi phạm. */
    private List<Long> invariantViolations() {
        return jdbc.queryForList("""
                SELECT id FROM care_tasks WHERE vaccination_id IN (SELECT id FROM vaccinations WHERE branch_id IN (?, ?))
                  AND NOT (status = 'OPEN' AND result IS NULL AND note IS NULL AND cancel_reason IS NULL
                           AND handled_by IS NULL AND handled_at IS NULL)""", Long.class, fx.branch, fx.otherBranch);
    }
}
