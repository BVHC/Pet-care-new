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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.care.job.NotificationOutboxJob;
import com.petcare.module.visit.job.FollowUpReminderJob;
import com.petcare.module.visit.service.FollowUpReminderService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

/**
 * ST04 nhắc tái khám (T39 phần 4, BR-TB-06, BR-TB-02, docs/adr/0082) trên Postgres 17 thật. Đồng hồ {@link MutableClock}
 * ở <b>năm 2022</b> (VaccineReminderIT dùng năm 2021). Cùng khuôn với {@link VaccineReminderIT}: dòng nào được nhắc, kênh
 * theo BR-TB-02, thú đã mất ({@code follow_up_cancelled_at}), chạy trùng (hai lượt song song, chạy lại), lỗi sau khi đặt
 * cờ rollback cả cờ, mẫu V22 render được qua ST20.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FollowUpReminderIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class FollowUpReminderIT {

    private static final LocalDate TODAY = LocalDate.of(2022, 6, 1);
    /** 01/06/2022 10:00 giờ Việt Nam. */
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final String[] CODES = {"FOLLOW_UP_REMINDER", "FOLLOW_UP_REMINDER_APP"};

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
    private FollowUpReminderJob job;
    @Autowired
    private FollowUpReminderService service;
    @Autowired
    private InAppNotificationJob inAppJob;
    @Autowired
    private NotificationOutboxJob emailJob;

    private ReminderItFixture fx;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        fx = new ReminderItFixture(jdbc);
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS follow_up_reminder_it_fail ON notification_outbox");
        jdbc.execute("DROP TRIGGER IF EXISTS follow_up_reminder_it_fail_task ON care_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS follow_up_reminder_it_fail()");
        fx.cleanUp();
    }

    /** Bệnh án của một lượt COMPLETED mới của {@code customer} có hẹn tái khám {@code days} ngày nữa; trả id lượt. */
    private long record(long customer, long pet, long branch, int days) {
        long visit = fx.completedVisit(branch, customer, pet, T0);
        fx.medicalRecord(visit, TODAY.plusDays(days), T0);
        return visit;
    }

    // ================================================================ dòng nào được nhắc

    @Test
    void dueRecordOfAnAccountIsRemindedOnceAndOnlyTheFlagColumnsChange() {
        long pet = fx.pet(fx.online);
        long visit = record(fx.online, pet, fx.branch, 3);
        String before = rowWithoutFlag(visit);

        job.run();

        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("template_code"), r -> r.get("channel"),
                r -> r.get("recipient_account_id"), r -> r.get("recipient_email"), r -> r.get("link_url"))
                .containsExactlyInAnyOrder(
                        tuple("FOLLOW_UP_REMINDER_APP", "IN_APP", fx.account, null,
                                "/booking?petId=%d&branchId=%d".formatted(pet, fx.branch)),
                        tuple("FOLLOW_UP_REMINDER", "EMAIL", null, fx.accountEmail, null));
        Map<String, Object> payload = payloadOf(pet);
        assertThat(payload).containsEntry("ten_khach", "Trần Thị Online").containsEntry("ten_thu_cung", fx.petName(pet))
                .containsEntry("ngay_tai_kham", "04/06/2022").containsEntry("chi_nhanh", branchName(fx.branch))
                .containsEntry("so_dien_thoai_chi_nhanh", "0280000000");
        assertThat(rowWithoutFlag(visit)).isEqualTo(before);
        Map<String, Object> marks = jdbc.queryForMap(
                "SELECT follow_up_reminded_at, follow_up_cancelled_at, updated_at FROM medical_records WHERE visit_id = ?",
                visit);
        assertThat(((Timestamp) marks.get("follow_up_reminded_at")).toInstant()).isEqualTo(T0);
        assertThat(marks.get("updated_at")).isEqualTo(marks.get("follow_up_reminded_at"));
        assertThat(marks.get("follow_up_cancelled_at")).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
    }

    @Test
    void windowBoundsAreInclusiveAndOutsideIsIgnored() {
        Map<Integer, Long> pets = new HashMap<>();
        Map<Integer, Long> visits = new HashMap<>();
        for (int offset : List.of(-1, 0, 3, 4)) {
            long pet = fx.pet(fx.counter);
            pets.put(offset, pet);
            visits.put(offset, record(fx.counter, pet, fx.branch, offset));
        }
        assertThat(service.findDueIds(service.window(), 0, 1000)).contains(visits.get(0), visits.get(3))
                .doesNotContain(visits.get(-1), visits.get(4));

        job.run();

        assertThat(fx.outbox(pets.get(-1), CODES)).isEmpty();
        assertThat(fx.outbox(pets.get(0), CODES)).hasSize(1);
        assertThat(fx.outbox(pets.get(3), CODES)).hasSize(1);
        assertThat(fx.outbox(pets.get(4), CODES)).isEmpty();
    }

    /** Bệnh án chưa khóa, không có hẹn, đã nhắc, đã hủy nhắc đều không được nhắc. */
    @Test
    void onlyLockedUnremindedUncancelledRecordsWithADateAreReminded() {
        long open = fx.pet(fx.counter);
        long openVisit = fx.visit(fx.branch, fx.counter, open, "IN_PROGRESS", T0);
        fx.medicalRecord(openVisit, TODAY.plusDays(2), null);
        long noDate = fx.pet(fx.counter);
        fx.medicalRecord(fx.completedVisit(fx.branch, fx.counter, noDate, T0), null, T0);
        long reminded = fx.pet(fx.counter);
        long remindedVisit = record(fx.counter, reminded, fx.branch, 2);
        jdbc.update("UPDATE medical_records SET follow_up_reminded_at = ? WHERE visit_id = ?", Timestamp.from(T0),
                remindedVisit);
        long cancelled = fx.pet(fx.counter);
        long cancelledVisit = record(fx.counter, cancelled, fx.branch, 2);
        jdbc.update("UPDATE medical_records SET follow_up_cancelled_at = ? WHERE visit_id = ?", Timestamp.from(T0),
                cancelledVisit);
        long due = fx.pet(fx.counter);
        long dueVisit = record(fx.counter, due, fx.branch, 2);

        List<Long> scanned = service.findDueIds(service.window(), 0, 1000);
        job.run();

        assertThat(scanned).contains(dueVisit).doesNotContain(openVisit, remindedVisit, cancelledVisit);
        assertThat(fx.outbox(open, CODES)).isEmpty();
        assertThat(fx.outbox(noDate, CODES)).isEmpty();
        assertThat(fx.outbox(reminded, CODES)).isEmpty();
        assertThat(fx.outbox(cancelled, CODES)).isEmpty();
        assertThat(fx.outbox(due, CODES)).hasSize(1);
    }

    // ================================================================ BR-TB-02 — kênh

    @Test
    void counterProfileWithEmailGetsOnlyAnEmail() {
        long pet = fx.pet(fx.counter);
        record(fx.counter, pet, fx.branch, 2);

        job.run();

        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("template_code"), r -> r.get("channel"),
                r -> r.get("recipient_email"), r -> r.get("link_url"))
                .containsExactly(tuple("FOLLOW_UP_REMINDER", "EMAIL", fx.counterEmail, null));
        assertThat(fx.tasks(pet)).isEmpty();
    }

    /** Hồ sơ không email: không outbox, đúng một Care Task FOLLOW_UP_DUE cho chi nhánh của lượt có hẹn. */
    @Test
    void profileWithoutEmailGetsAFollowUpTaskForTheBranchOfTheVisit() {
        long pet = fx.pet(fx.silent);
        long visit = record(fx.silent, pet, fx.otherBranch, 2);

        job.run();

        assertThat(fx.outbox(pet, CODES)).isEmpty();
        assertThat(fx.tasks(pet)).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("task_type", "FOLLOW_UP_DUE").containsEntry("status", "OPEN")
                    .containsEntry("branch_id", fx.otherBranch).containsEntry("customer_id", fx.silent)
                    .containsEntry("medical_record_id", visit);
            assertThat(task.get("due_date").toString()).isEqualTo(TODAY.plusDays(2).toString());
        });
        assertThat(remindedAt(visit)).isNotNull();
    }

    /** Thú đã mất: nhắc bị hủy (BR-TB-06) — đặt {@code follow_up_cancelled_at}, không đánh dấu đã nhắc, không gửi, không task. */
    @Test
    void deceasedPetCancelsTheReminderInsteadOfSendingIt() {
        long pet = fx.pet(fx.silent);
        long visit = record(fx.silent, pet, fx.branch, 2);
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);

        job.run();

        assertThat(fx.outbox(pet, CODES)).isEmpty();
        assertThat(fx.tasks(pet)).isEmpty();
        assertThat(remindedAt(visit)).isNull();
        assertThat(cancelledAt(visit)).isNotNull();
        assertThat(service.findDueIds(service.window(), 0, 1000)).doesNotContain(visit);
    }

    @Test
    void reminderGoesToTheCurrentOwnerOfThePet() {
        long pet = fx.pet(fx.silent);
        record(fx.silent, pet, fx.branch, 2);
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", fx.counter, pet);

        job.run();

        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("recipient_email")).containsExactly(fx.counterEmail);
        assertThat(fx.tasks(pet)).isEmpty();
    }

    // ================================================================ chạy trùng

    @Test
    void twoConcurrentRunsProcessEachRecordExactlyOnce(CapturedOutput output) throws Exception {
        Map<Long, Integer> expectedOutbox = new HashMap<>();
        int silentCount = 0;
        for (int i = 0; i < 16; i++) {
            long customer = switch (i % 4) {
                case 0 -> fx.online;
                case 1 -> fx.counter;
                default -> fx.silent;
            };
            long pet = fx.pet(customer);
            record(customer, pet, i % 2 == 0 ? fx.branch : fx.otherBranch, 1 + i % 3);
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
        assertThat(fx.taskCount()).isEqualTo(silentCount);
        assertThat(output).doesNotContain("FOLLOW_UP_REMINDER_FAILED");
    }

    @Test
    void rerunSendsNothingAndWritesNothing() {
        List<Long> visits = new ArrayList<>();
        List<Long> pets = new ArrayList<>();
        for (long customer : List.of(fx.online, fx.counter, fx.silent)) {
            long pet = fx.pet(customer);
            pets.add(pet);
            visits.add(record(customer, pet, fx.branch, 2));
        }
        long deceased = fx.pet(fx.silent);
        pets.add(deceased);
        visits.add(record(fx.silent, deceased, fx.branch, 2));
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), deceased);
        job.run();
        long outboxAfterFirst = pets.stream().mapToLong(p -> fx.outbox(p, CODES).size()).sum();
        long tasksAfterFirst = fx.taskCount();
        Map<Long, String> versions = new HashMap<>();
        visits.forEach(v -> versions.put(v, xmin(v)));

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(outboxAfterFirst).isEqualTo(3);
        assertThat(pets.stream().mapToLong(p -> fx.outbox(p, CODES).size()).sum()).isEqualTo(outboxAfterFirst);
        assertThat(fx.taskCount()).isEqualTo(tasksAfterFirst).isEqualTo(1);
        visits.forEach(v -> assertThat(xmin(v)).as("bệnh án %d", v).isEqualTo(versions.get(v)));
    }

    @Test
    void outboxFailureRollsBackTheFlagAndTheNextRunSucceeds(CapturedOutput output) {
        long pet = fx.pet(fx.online);
        long visit = record(fx.online, pet, fx.branch, 2);
        failInsertsOn("notification_outbox", "follow_up_reminder_it_fail",
                "NEW.template_code IN ('FOLLOW_UP_REMINDER', 'FOLLOW_UP_REMINDER_APP')");

        job.run();

        assertThat(output).contains("FOLLOW_UP_REMINDER_FAILED visitId=" + visit);
        assertThat(remindedAt(visit)).isNull();
        assertThat(fx.outbox(pet, CODES)).isEmpty();
        jdbc.execute("DROP TRIGGER follow_up_reminder_it_fail ON notification_outbox");

        job.run();

        assertThat(remindedAt(visit)).isNotNull();
        assertThat(fx.outbox(pet, CODES)).hasSize(2);
    }

    @Test
    void taskFailureRollsBackTheFlagAndTheNextRunSucceeds(CapturedOutput output) {
        long pet = fx.pet(fx.silent);
        long visit = record(fx.silent, pet, fx.branch, 2);
        failInsertsOn("care_tasks", "follow_up_reminder_it_fail_task", "NEW.task_type = 'FOLLOW_UP_DUE'");

        job.run();

        assertThat(output).contains("FOLLOW_UP_REMINDER_FAILED visitId=" + visit);
        assertThat(remindedAt(visit)).isNull();
        assertThat(fx.tasks(pet)).isEmpty();
        jdbc.execute("DROP TRIGGER follow_up_reminder_it_fail_task ON care_tasks");

        job.run();

        assertThat(remindedAt(visit)).isNotNull();
        assertThat(fx.tasks(pet)).hasSize(1);
    }

    @Test
    void failedDeliveryIsNotRemindedAgain() {
        long pet = fx.pet(fx.counter);
        record(fx.counter, pet, fx.branch, 2);
        job.run();
        jdbc.update("UPDATE notification_outbox SET status = 'FAILED' WHERE payload->>'ten_thu_cung' = ?",
                fx.petName(pet));

        clock.advance(Duration.ofHours(1));
        job.run();

        assertThat(fx.outbox(pet, CODES)).hasSize(1).extracting(r -> r.get("status")).containsExactly("FAILED");
    }

    // ================================================================ ST20 — mẫu V22 gửi được từ đầu tới cuối

    @Test
    void outboxRowsRenderWithTheV22TemplatesThroughSt20() throws Exception {
        long pet = fx.pet(fx.online);
        record(fx.online, pet, fx.branch, 3);
        job.run();

        MimeMessage mail = awaitMail(fx.accountEmail);
        inAppJob.run();

        assertThat(mail.getSubject()).isEqualTo("Nhắc tái khám của %s ngày 04/06/2022".formatted(fx.petName(pet)));
        assertThat(mail.getContent().toString()).contains("Xin chào Trần Thị Online")
                .contains("tại " + branchName(fx.branch)).contains("0280000000");
        assertThat(fx.outbox(pet, CODES)).extracting(r -> r.get("status")).containsOnly("SENT");
        assertThat(jdbc.queryForList("SELECT type, title, body, link_url FROM notifications WHERE account_id = ?",
                fx.account)).singleElement().satisfies(n -> {
                    assertThat(n).containsEntry("type", "FOLLOW_UP_REMINDER").containsEntry("link_url",
                            "/booking?petId=%d&branchId=%d".formatted(pet, fx.branch));
                    assertThat(n.get("title").toString()).contains(fx.petName(pet));
                    assertThat(n.get("body").toString()).contains("04/06/2022");
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
                CREATE OR REPLACE FUNCTION follow_up_reminder_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$""");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table + " FOR EACH ROW WHEN (" + condition
                + ") EXECUTE FUNCTION follow_up_reminder_it_fail()");
    }

    private Instant remindedAt(long visit) {
        Timestamp at = jdbc.queryForObject("SELECT follow_up_reminded_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
        return at == null ? null : at.toInstant();
    }

    private Instant cancelledAt(long visit) {
        Timestamp at = jdbc.queryForObject("SELECT follow_up_cancelled_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
        return at == null ? null : at.toInstant();
    }

    private String rowWithoutFlag(long visit) {
        return jdbc.queryForObject("SELECT (to_jsonb(m) - 'follow_up_reminded_at' - 'updated_at')::text "
                + "FROM medical_records m WHERE visit_id = ?", String.class, visit);
    }

    private String xmin(long visit) {
        return jdbc.queryForObject("SELECT xmin::text FROM medical_records WHERE visit_id = ?", String.class, visit);
    }

    private String branchName(long branchId) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branchId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(long pet) {
        try {
            return new ObjectMapper().readValue(
                    fx.outbox(pet, "FOLLOW_UP_REMINDER").get(0).get("payload").toString(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
