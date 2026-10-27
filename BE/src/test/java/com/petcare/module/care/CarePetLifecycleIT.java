package com.petcare.module.care;

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
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * ST19 (Care Task#4, BR-KH-05) và chuyển chủ (06 §8 Q1, BR-KH-08) phía care, docs/adr/0084, trên Postgres 17 thật. Sự kiện
 * phát trong transaction của test như {@code PetLifecycleService}; hai listener của care chạy thật. Phủ cả bốn loại task
 * (kể cả {@code PICKUP_OVERDUE}, mà IT bên visit không dựng được). Đồng hồ {@link MutableClock} ở <b>năm 2023</b>.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CarePetLifecycleIT.TestBeans.class})
class CarePetLifecycleIT {

    private static final LocalDate TODAY = LocalDate.of(2023, 6, 1);
    private static final Instant T0 = ZonedDateTime.of(TODAY, LocalTime.of(10, 0), TimeConfig.BUSINESS_ZONE).toInstant();
    private static final String DECEASED_REASON = "Thú cưng đã mất (BR-KH-05)";

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
    private ApplicationEventPublisher publisher;

    private TransactionTemplate tx;
    private CareTaskItSupport data;
    private long branch;
    private long owner;
    private long pet;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new CareTaskItSupport(jdbc, transactionManager);
        branch = data.branch();
        owner = data.customer();
        pet = data.pet(owner);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS care_lifecycle_it_fail ON care_tasks");
        jdbc.execute("DROP FUNCTION IF EXISTS care_lifecycle_it_fail()");
        data.cleanUp();
    }

    /** Bốn task {@code OPEN} đủ bốn loại của {@code pet}, theo thứ tự VACCINE_DUE, VACCINE_OVERDUE, FOLLOW_UP_DUE, PICKUP_OVERDUE. */
    private long[] openTasksOf(long petId, long customerId) {
        return new long[] {
                data.task("VACCINE_DUE", data.vaccination(petId), branch, customerId, petId, "OPEN"),
                data.task("VACCINE_OVERDUE", data.vaccination(petId), branch, customerId, petId, "OPEN"),
                data.task("FOLLOW_UP_DUE", data.medicalRecord(branch, customerId, petId), branch, customerId, petId,
                        "OPEN"),
                data.task("PICKUP_OVERDUE", data.booking(branch, customerId, petId, 0), branch, customerId, petId,
                        "OPEN")};
    }

    private Map<String, Object> row(long task) {
        return jdbc.queryForMap(
                "SELECT status, customer_id, cancel_reason, handled_by, handled_at FROM care_tasks WHERE id = ?", task);
    }

    /** Các cột mà chuyển chủ không được đổi (Q1: giữ nguyên loại, chi nhánh phụ trách, hạn, nguồn). */
    private Map<String, Object> fixedColumns(long task) {
        return jdbc.queryForMap("""
                SELECT task_type, branch_id, due_date, vaccination_id, medical_record_id, boarding_booking_id
                FROM care_tasks WHERE id = ?""", task);
    }

    @Test
    void deceasedEventCancelsEveryOpenTaskOfEveryTypeOfThePetAndNothingElse() {
        long[] open = openTasksOf(pet, owner);
        long done = data.task("FOLLOW_UP_DUE", data.medicalRecord(branch, owner, pet), branch, owner, pet, "DONE");
        long staffCancelled = data.task("VACCINE_DUE", data.vaccination(pet), branch, owner, pet, "CANCELLED");
        long otherPet = data.pet(owner);
        long[] otherOpen = openTasksOf(otherPet, owner);

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            publisher.publishEvent(new PetDeceasedEvent(pet, owner, TODAY.minusDays(1), 999_999L));
        });

        for (long task : open) {
            Map<String, Object> row = row(task);
            assertThat(row).as("task %d", task).containsEntry("status", "CANCELLED")
                    .containsEntry("cancel_reason", DECEASED_REASON).containsEntry("handled_by", null);
            assertThat(((Timestamp) row.get("handled_at")).toInstant()).isEqualTo(T0);
        }
        assertThat(row(done)).containsEntry("status", "DONE").containsEntry("cancel_reason", null);
        assertThat(row(staffCancelled)).containsEntry("cancel_reason", "Lễ tân hủy");
        for (long task : otherOpen) {
            assertThat(row(task)).as("thú khác, task %d", task).containsEntry("status", "OPEN");
        }
        assertThat(data.invariantViolations()).isEmpty();
    }

    @Test
    void ownerTransferMovesEveryOpenTaskToTheNewOwnerAndKeepsClosedOnesWithTheOldOne() {
        long newOwner = data.customer();
        long[] open = openTasksOf(pet, owner);
        long done = data.task("FOLLOW_UP_DUE", data.medicalRecord(branch, owner, pet), branch, owner, pet, "DONE");
        Map<Long, Map<String, Object>> before = new java.util.HashMap<>();
        for (long task : open) {
            before.put(task, fixedColumns(task));
        }

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner, pet);
            publisher.publishEvent(new PetOwnerTransferredEvent(pet, owner, newOwner, 999_999L));
        });

        for (int i = 0; i < open.length; i++) {
            assertThat(row(open[i])).as("task %d", open[i]).containsEntry("status", "OPEN")
                    .containsEntry("customer_id", newOwner);
            assertThat(fixedColumns(open[i])).as("loại, chi nhánh, hạn, nguồn giữ nguyên (Q1)")
                    .isEqualTo(before.get(open[i]));
        }
        assertThat(row(done)).containsEntry("customer_id", owner);
        assertThat(data.invariantViolations()).isEmpty();
    }

    @Test
    void aFailingTaskUpdateRollsBackThePetChangeOfBothEvents() {
        long newOwner = data.customer();
        long[] open = openTasksOf(pet, owner);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION care_lifecycle_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'IT: care_tasks UPDATE bị từ chối'; END $$""");
        jdbc.execute("CREATE TRIGGER care_lifecycle_it_fail BEFORE UPDATE ON care_tasks FOR EACH ROW WHEN "
                + "(NEW.pet_id = " + pet + ") EXECUTE FUNCTION care_lifecycle_it_fail()");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY.minusDays(1), pet);
            publisher.publishEvent(new PetDeceasedEvent(pet, owner, TODAY.minusDays(1), 999_999L));
        })).hasMessageContaining("care_tasks UPDATE bị từ chối");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner, pet);
            publisher.publishEvent(new PetOwnerTransferredEvent(pet, owner, newOwner, 999_999L));
        })).hasMessageContaining("care_tasks UPDATE bị từ chối");

        assertThat(jdbc.queryForObject("SELECT deceased_on FROM pets WHERE id = ?", java.sql.Date.class, pet)).isNull();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet)).isEqualTo(owner);
        for (long task : open) {
            assertThat(row(task)).containsEntry("status", "OPEN").containsEntry("customer_id", owner);
        }
    }
}
