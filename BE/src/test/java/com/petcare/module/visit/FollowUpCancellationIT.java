package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;
import com.petcare.module.visit.api.FollowUpApi;
import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.FollowUpReminderService;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Hủy nhắc tái khám và Care Task (T39 phần 6, BR-TB-06, BR-TB-03, 06 §8 Q1, docs/adr/0084) trên Postgres 17 thật. Đồng hồ
 * {@link MutableClock} ở <b>năm 2023</b> (ST04 dùng 2021 / 2022, ST18 dùng 1990). {@link FollowUpApi} chỉ hủy bệnh án
 * <b>đã khóa</b>, chưa nhắc; task {@code FOLLOW_UP_DUE} {@code OPEN} của bệnh án đã nhắc; ST04 bỏ qua bệnh án đã hủy nhắc;
 * sự kiện thú mất / chuyển chủ đi qua đúng các listener của visit và care trong một transaction, listener lỗi thì rollback
 * cả phần visit đã làm trước đó.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FollowUpCancellationIT.TestBeans.class})
class FollowUpCancellationIT {

    private static final LocalDate TODAY = LocalDate.of(2023, 6, 1);
    /** 01/06/2023 10:00 giờ Việt Nam. */
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final Instant PAST = T0.minusSeconds(40L * 86_400);

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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FollowUpApi followUps;
    @Autowired
    private FollowUpReminderService reminders;
    @Autowired
    private ApplicationEventPublisher publisher;

    private TransactionTemplate tx;
    private ReminderItFixture fx;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        fx = new ReminderItFixture(jdbc);
        fx.setUp();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS follow_up_cancel_it_fail ON care_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS follow_up_cancel_it_fail()");
        fx.cleanUp();
    }

    private long record(long pet, LocalDate followUp, boolean locked, boolean reminded) {
        return FollowUpItSupport.record(jdbc, fx.branch, fx.counter, pet, fx.vet, PAST, followUp, locked, reminded);
    }

    private long task(String type, long source, long pet, String status) {
        return FollowUpItSupport.task(jdbc, type, source, fx.branch, fx.counter, pet, status, fx.vet);
    }

    private void cancel(long pet, Instant at) {
        tx.executeWithoutResult(s -> followUps.cancelPendingFollowUps(pet, at));
    }

    private Instant cancelledAt(long visit) {
        return FollowUpItSupport.cancelledAt(jdbc, visit);
    }

    private String status(long task) {
        return FollowUpItSupport.taskStatus(jdbc, task);
    }

    // ================================================================ FollowUpApi

    @Test
    void cancelsPendingRemindersOfLockedRecordsOfThePetOnly() {
        long pet = fx.pet(fx.counter);
        long otherPet = fx.pet(fx.counter);
        long target = record(pet, TODAY.plusDays(20), true, false);
        long unlocked = record(pet, TODAY.plusDays(20), false, false);
        long reminded = record(pet, TODAY.plusDays(20), true, true);
        long noDate = record(pet, null, true, false);
        long otherPetRecord = record(otherPet, TODAY.plusDays(20), true, false);
        Instant at = T0.plusSeconds(5);

        cancel(pet, at);

        assertThat(cancelledAt(target)).isEqualTo(at);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM medical_records WHERE visit_id = ?", Timestamp.class,
                target).toInstant()).as("updated_at đặt từ mốc của use case").isEqualTo(at);
        assertThat(cancelledAt(unlocked)).as("lượt đang khám: ngày tái khám còn sửa được").isNull();
        assertThat(cancelledAt(reminded)).as("đã nhắc rồi, không phải nhắc chờ gửi").isNull();
        assertThat(cancelledAt(noDate)).isNull();
        assertThat(cancelledAt(otherPetRecord)).as("thú khác").isNull();
    }

    @Test
    void cancelsOpenFollowUpTasksOfRemindedRecordsAndNothingElse() {
        long pet = fx.pet(fx.counter);
        long otherPet = fx.pet(fx.counter);
        long open = record(pet, TODAY.plusDays(2), true, true);
        long done = record(pet, TODAY.plusDays(3), true, true);
        long cancelledByStaff = record(pet, TODAY.plusDays(4), true, true);
        long otherPetRecord = record(otherPet, TODAY.plusDays(2), true, true);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.counter, pet, PAST), TODAY.plusDays(2));
        long openTask = task("FOLLOW_UP_DUE", open, pet, "OPEN");
        long doneTask = task("FOLLOW_UP_DUE", done, pet, "DONE");
        long staffCancelled = task("FOLLOW_UP_DUE", cancelledByStaff, pet, "CANCELLED");
        long vaccineTask = task("VACCINE_DUE", dose, pet, "OPEN");
        long otherPetTask = task("FOLLOW_UP_DUE", otherPetRecord, otherPet, "OPEN");

        cancel(pet, T0);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, cancel_reason, handled_by, handled_at FROM care_tasks WHERE id = ?", openTask);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("handled_by", null);
        assertThat((String) row.get("cancel_reason")).contains("BR-TB-06");
        assertThat(((Timestamp) row.get("handled_at")).toInstant()).isEqualTo(T0);
        assertThat(status(doneTask)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT handled_by FROM care_tasks WHERE id = ?", Long.class, doneTask))
                .as("task lễ tân đã đóng giữ nguyên người xử lý").isEqualTo(fx.vet);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, staffCancelled))
                .isEqualTo("Lễ tân hủy");
        assertThat(status(vaccineTask)).as("task tái chủng không thuộc BR-TB-06").isEqualTo("OPEN");
        assertThat(status(otherPetTask)).isEqualTo("OPEN");
        assertThat(cancelledAt(open)).as("bệnh án đã nhắc không bị đặt cờ hủy nhắc").isNull();
    }

    @Test
    void st04SkipsARecordWhoseReminderWasCancelled() {
        long pet = fx.pet(fx.silent);
        long visit = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(3), true,
                false);
        DueDateWindow window = reminders.window();

        cancel(pet, T0);

        assertThat(reminders.remind(visit, window)).isEqualTo(ReminderResult.SKIPPED);
        assertThat(fx.outbox(pet, "FOLLOW_UP_REMINDER", "FOLLOW_UP_REMINDER_APP")).isEmpty();
        assertThat(fx.tasks(pet)).isEmpty();
    }

    @Test
    void callingTwiceIsHarmless() {
        long pet = fx.pet(fx.counter);
        long reminded = record(pet, TODAY.plusDays(2), true, true);
        long openTask = task("FOLLOW_UP_DUE", reminded, pet, "OPEN");

        cancel(pet, T0);
        cancel(pet, T0.plusSeconds(60));

        assertThat(status(openTask)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT handled_at FROM care_tasks WHERE id = ?", Timestamp.class, openTask)
                .toInstant()).as("lần hai không ghi đè").isEqualTo(T0);
    }

    // ================================================================ sự kiện thú mất / chuyển chủ

    @Test
    void deceasedEventCancelsPendingRemindersAndEveryOpenTaskOfThePetInOneTransaction() {
        long pet = fx.pet(fx.silent);
        long otherPet = fx.pet(fx.silent);
        long pending = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(20), true,
                false);
        long reminded = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(2), true,
                true);
        long dose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, PAST), TODAY.plusDays(2));
        long overdueDose = fx.dose(fx.completedVisit(fx.branch, fx.silent, pet, PAST), TODAY.minusDays(8));
        long followUpTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", reminded, fx.branch, fx.silent, pet, "OPEN",
                fx.vet);
        long vaccineDue = FollowUpItSupport.task(jdbc, "VACCINE_DUE", dose, fx.branch, fx.silent, pet, "OPEN", fx.vet);
        long vaccineOverdue = FollowUpItSupport.task(jdbc, "VACCINE_OVERDUE", overdueDose, fx.branch, fx.silent, pet,
                "OPEN", fx.vet);
        long doneTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", pending, fx.branch, fx.silent, pet, "DONE", fx.vet);
        long otherRecord = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, otherPet, fx.vet, PAST,
                TODAY.plusDays(20), true, false);
        long otherTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", otherRecord, fx.branch, fx.silent, otherPet,
                "OPEN", fx.vet);

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            publisher.publishEvent(new PetDeceasedEvent(pet, fx.silent, TODAY.minusDays(1), fx.vet));
        });

        assertThat(cancelledAt(pending)).as("visit: nhắc chờ gửi bị hủy").isEqualTo(T0);
        assertThat(cancelledAt(reminded)).as("đã nhắc rồi").isNull();
        for (long cancelled : new long[] {followUpTask, vaccineDue, vaccineOverdue}) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, cancel_reason, handled_by, handled_at FROM care_tasks WHERE id = ?", cancelled);
            assertThat(row).as("care: task %d", cancelled).containsEntry("status", "CANCELLED")
                    .containsEntry("cancel_reason", "Thú cưng đã mất (BR-KH-05)").containsEntry("handled_by", null);
            assertThat(((Timestamp) row.get("handled_at")).toInstant()).isEqualTo(T0);
        }
        assertThat(status(doneTask)).isEqualTo("DONE");
        assertThat(cancelledAt(otherRecord)).as("thú khác").isNull();
        assertThat(status(otherTask)).isEqualTo("OPEN");
    }

    @Test
    void aFailureInTheCareListenerRollsBackWhatTheVisitListenerAlreadyDid() {
        long pet = fx.pet(fx.silent);
        long pending = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(20), true,
                false);
        long reminded = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(2), true,
                true);
        long openTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", reminded, fx.branch, fx.silent, pet, "OPEN",
                fx.vet);
        // Trigger báo trong thông báo lỗi số bệnh án của thú đã bị hủy nhắc tại thời điểm care chạm care_tasks: 1 nghĩa là
        // listener của visit đã chạy TRƯỚC listener của care (medical_records trước care_tasks, ADR-0084 mục 8).
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION follow_up_cancel_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: care_tasks UPDATE bị từ chối, visit_done=%', (
                        SELECT count(*) FROM medical_records m JOIN visits v ON v.id = m.visit_id
                        WHERE v.pet_id = NEW.pet_id AND m.follow_up_cancelled_at IS NOT NULL);
                END $$""");
        jdbc.execute("CREATE TRIGGER follow_up_cancel_it_fail BEFORE UPDATE ON care_tasks FOR EACH ROW WHEN "
                + "(NEW.pet_id = " + pet + ") EXECUTE FUNCTION follow_up_cancel_it_fail()");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            publisher.publishEvent(new PetDeceasedEvent(pet, fx.silent, TODAY.minusDays(1), fx.vet));
        })).hasMessageContaining("care_tasks UPDATE bị từ chối").hasMessageContaining("visit_done=1");

        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", java.sql.Date.class, pet))
                .as("thú không bị đánh dấu mất").isNull();
        assertThat(cancelledAt(pending)).as("listener của visit chạy trước đã bị rollback").isNull();
        assertThat(status(openTask)).isEqualTo("OPEN");
    }

    @Test
    void ownerTransferMovesOpenTasksToTheNewOwnerAndRemindersTravelWithThePet() {
        long pet = fx.pet(fx.silent);
        long pending = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(20), true,
                false);
        long reminded = FollowUpItSupport.record(jdbc, fx.branch, fx.silent, pet, fx.vet, PAST, TODAY.plusDays(2), true,
                true);
        long openTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", reminded, fx.branch, fx.silent, pet, "OPEN",
                fx.vet);
        long doneTask = FollowUpItSupport.task(jdbc, "FOLLOW_UP_DUE", pending, fx.branch, fx.silent, pet, "DONE", fx.vet);
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT task_type, branch_id, due_date, medical_record_id FROM care_tasks WHERE id = ?", openTask);

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", fx.counter, pet);
            publisher.publishEvent(new PetOwnerTransferredEvent(pet, fx.silent, fx.counter, fx.vet));
        });

        assertThat(jdbc.queryForObject("SELECT customer_id FROM care_tasks WHERE id = ?", Long.class, openTask))
                .isEqualTo(fx.counter);
        assertThat(jdbc.queryForMap(
                "SELECT task_type, branch_id, due_date, medical_record_id FROM care_tasks WHERE id = ?", openTask))
                .as("loại, chi nhánh, hạn và nguồn giữ nguyên (Q1)").isEqualTo(before);
        assertThat(status(openTask)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM care_tasks WHERE id = ?", Long.class, doneTask))
                .as("task đã đóng giữ chủ lúc đóng").isEqualTo(fx.silent);
        assertThat(cancelledAt(pending)).as("nhắc tái khám đi theo thú, không bị hủy").isNull();
    }
}
