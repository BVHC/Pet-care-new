package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.support.MutableClock;

/**
 * {@link CareTaskApi} trên Postgres thật (T39 phần 0, docs/adr/0079, trả D047): {@code create} ghi đúng cột cho cả 4 loại,
 * chống trùng hai loại quá hạn (trả id cũ dù task cũ ở trạng thái nào) và không chống trùng hai loại nhắc; hai câu hủy
 * hệ thống chỉ chuyển task {@code OPEN} đúng loại và đúng nguồn (ma trận trạng thái × loại × nguồn, dòng khác giữ
 * nguyên {@code xmin}); mọi thứ đi theo transaction của bên gọi. Cuối mỗi ca, bất biến giữa các cột theo trạng thái (DB
 * chưa có CHECK — D049) phải đúng. Đồng hồ {@link MutableClock} ở <b>năm 2018</b>.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CareTaskApiIT.TestBeans.class})
class CareTaskApiIT {

    private static final Instant T0 = Instant.parse("2018-06-12T03:00:00Z");
    private static final Instant T1 = Instant.parse("2018-06-12T09:30:00Z");
    private static final LocalDate DUE = LocalDate.of(2018, 6, 20);
    private static final long MISSING = 999_999_998L;
    private static final List<String> STATUSES = List.of("OPEN", "DONE", "CANCELLED");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private CareTaskApi api;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;

    private CareTaskItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long customer;
    private long pet;
    private long vaccination;
    private long medicalRecord;
    private long booking;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new CareTaskItSupport(jdbc, transactionManager);
        branch = data.branch();
        customer = data.customer();
        pet = data.pet(customer);
        vaccination = data.vaccination(pet);
        medicalRecord = data.medicalRecord(branch, customer, pet);
        booking = data.booking(branch, customer, pet, 10);
    }

    @AfterEach
    void tearDown() {
        try {
            assertThat(data.invariantViolations()).as("bất biến trạng thái ↔ cột xử lý (D049)").isEmpty();
        } finally {
            data.cleanUp();
        }
    }

    // ================================================================ helpers

    private NewCareTask task(CareTaskType type, long source) {
        return new NewCareTask(type, source, branch, customer, pet, DUE);
    }

    private long create(CareTaskType type, long source) {
        return tx.execute(status -> api.create(task(type, source)));
    }

    private long sourceOf(CareTaskType type) {
        return switch (type) {
            case VACCINE_DUE, VACCINE_OVERDUE -> vaccination;
            case FOLLOW_UP_DUE -> medicalRecord;
            case PICKUP_OVERDUE -> booking;
        };
    }

    private String xmin(long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM care_tasks WHERE id = ?", String.class, id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM care_tasks WHERE id = ?", String.class, id);
    }

    private Instant instant(String column, long id) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM care_tasks WHERE id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private long countBy(String column, long source) {
        return jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE " + column + " = ?", Long.class, source);
    }

    // ================================================================ create

    @Test
    void createsAnOpenTaskWithTheSourceInTheRightColumnForEveryType() {
        for (CareTaskType type : CareTaskType.values()) {
            long source = sourceOf(type);
            long id = create(type, source);

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM care_tasks WHERE id = ?", id);
            assertThat(row).as(type.name()).containsEntry("task_type", type.name()).containsEntry("status", "OPEN")
                    .containsEntry("branch_id", branch).containsEntry("customer_id", customer)
                    .containsEntry("pet_id", pet);
            assertThat(((java.sql.Date) row.get("due_date")).toLocalDate()).isEqualTo(DUE);
            assertThat(row.get("vaccination_id")).as(type.name()).isEqualTo(
                    type == CareTaskType.VACCINE_DUE || type == CareTaskType.VACCINE_OVERDUE ? (Object) source : null);
            assertThat(row.get("medical_record_id")).as(type.name())
                    .isEqualTo(type == CareTaskType.FOLLOW_UP_DUE ? (Object) source : null);
            assertThat(row.get("boarding_booking_id")).as(type.name())
                    .isEqualTo(type == CareTaskType.PICKUP_OVERDUE ? (Object) source : null);
            assertThat(row.get("result")).isNull();
            assertThat(row.get("note")).isNull();
            assertThat(row.get("cancel_reason")).isNull();
            assertThat(row.get("handled_by")).isNull();
            assertThat(row.get("handled_at")).isNull();
            assertThat(instant("created_at", id)).as("created_at từ Clock (" + type + ")").isEqualTo(T0);
            assertThat(instant("updated_at", id)).as("updated_at từ Clock (" + type + ")").isEqualTo(T0);
        }
    }

    @Test
    void theNativeInsertAndTheEntityInsertWriteTheSameColumnsForTheSameInput() {
        long due = create(CareTaskType.VACCINE_DUE, vaccination);
        long overdue = create(CareTaskType.VACCINE_OVERDUE, vaccination);

        String columns = "branch_id, customer_id, pet_id, vaccination_id, medical_record_id, boarding_booking_id, due_date,"
                + " status, result, note, cancel_reason, handled_by, handled_at, created_at, updated_at";
        Map<String, Object> viaEntity = jdbc.queryForMap("SELECT " + columns + " FROM care_tasks WHERE id = ?", due);
        Map<String, Object> viaNative = jdbc.queryForMap("SELECT " + columns + " FROM care_tasks WHERE id = ?", overdue);
        assertThat(viaNative).isEqualTo(viaEntity);
    }

    @Test
    void dueTypesAreNotDeduplicated() {
        long first = create(CareTaskType.VACCINE_DUE, vaccination);
        long second = create(CareTaskType.VACCINE_DUE, vaccination);
        long followFirst = create(CareTaskType.FOLLOW_UP_DUE, medicalRecord);
        long followSecond = create(CareTaskType.FOLLOW_UP_DUE, medicalRecord);

        assertThat(List.of(first, second, followFirst, followSecond)).doesNotHaveDuplicates();
        assertThat(countBy("vaccination_id", vaccination)).isEqualTo(2);
        assertThat(countBy("medical_record_id", medicalRecord)).isEqualTo(2);
    }

    @Test
    void anOverdueTaskCreatedTwiceReturnsTheFirstId() {
        long vaccine = create(CareTaskType.VACCINE_OVERDUE, vaccination);
        long pickup = create(CareTaskType.PICKUP_OVERDUE, booking);

        assertThat(create(CareTaskType.VACCINE_OVERDUE, vaccination)).isEqualTo(vaccine);
        assertThat(create(CareTaskType.PICKUP_OVERDUE, booking)).isEqualTo(pickup);
        assertThat(countBy("vaccination_id", vaccination)).isEqualTo(1);
        assertThat(countBy("boarding_booking_id", booking)).isEqualTo(1);
    }

    @Test
    void anExistingOverdueTaskIsReturnedWhateverItsStatusAndIsLeftUntouched() {
        int offset = 100;
        for (String status : STATUSES) {
            long dose = data.vaccination(pet);
            long existingVaccine = data.task("VACCINE_OVERDUE", dose, branch, customer, pet, status);
            String vaccineXmin = xmin(existingVaccine);
            long stay = data.booking(branch, customer, pet, offset);
            offset += 10;
            long existingPickup = data.task("PICKUP_OVERDUE", stay, branch, customer, pet, status);
            String pickupXmin = xmin(existingPickup);

            assertThat(create(CareTaskType.VACCINE_OVERDUE, dose)).as(status).isEqualTo(existingVaccine);
            assertThat(create(CareTaskType.PICKUP_OVERDUE, stay)).as(status).isEqualTo(existingPickup);

            assertThat(xmin(existingVaccine)).as("không UPDATE " + status).isEqualTo(vaccineXmin);
            assertThat(xmin(existingPickup)).as("không UPDATE " + status).isEqualTo(pickupXmin);
            assertThat(statusOf(existingVaccine)).isEqualTo(status);
            assertThat(countBy("vaccination_id", dose)).isEqualTo(1);
            assertThat(countBy("boarding_booking_id", stay)).isEqualTo(1);
        }
    }

    @Test
    void theTaskIsRolledBackWithTheCaller() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            api.create(task(CareTaskType.VACCINE_DUE, vaccination));
            api.create(task(CareTaskType.VACCINE_OVERDUE, vaccination));
            throw new IllegalStateException("bên gọi lỗi sau khi sinh task");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(data.countTasks()).isZero();
    }

    @Test
    void aForeignKeyFailureRollsBackEveryEarlierWriteOfTheTransaction() {
        for (CareTaskType type : new CareTaskType[] {CareTaskType.VACCINE_DUE, CareTaskType.VACCINE_OVERDUE}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                api.create(task(CareTaskType.FOLLOW_UP_DUE, medicalRecord));
                api.create(new NewCareTask(type, vaccination, branch, customer, MISSING, DUE));
            })).as(type.name()).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            api.create(task(CareTaskType.FOLLOW_UP_DUE, medicalRecord));
            api.create(new NewCareTask(CareTaskType.VACCINE_DUE, MISSING, branch, customer, pet, DUE));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(data.countTasks()).as("không còn dòng nào của các transaction lỗi").isZero();
    }

    @Test
    void everyMethodRefusesToRunOutsideTheCallersTransaction() {
        assertThatThrownBy(() -> api.create(task(CareTaskType.VACCINE_DUE, vaccination)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.cancelForRevaccination(List.of(vaccination)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.cancelFollowUpTasks(List.of(medicalRecord)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(data.countTasks()).isZero();
    }

    // ================================================================ hủy do hệ thống (Care Task#5, #6)

    /** Chụp trạng thái, {@code xmin} và lý do trước khi gọi, để so sau: dòng không được chạm phải y nguyên. */
    private record Before(String status, String xmin, String reason) {
    }

    private Map<Long, Before> snapshot(Iterable<Long> ids) {
        Map<Long, Before> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, new Before(statusOf(id), xmin(id),
                    jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, id)));
        }
        return result;
    }

    private void assertMatrix(Map<Long, Boolean> expectCancel, Map<Long, Before> before, String reason) {
        expectCancel.forEach((id, cancelled) -> {
            if (cancelled) {
                assertThat(statusOf(id)).as("task " + id).isEqualTo("CANCELLED");
                assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, id))
                        .isEqualTo(reason);
                assertThat(instant("handled_at", id)).isEqualTo(T1);
                assertThat(instant("updated_at", id)).isEqualTo(T1);
                assertThat(jdbc.queryForObject("SELECT handled_by FROM care_tasks WHERE id = ?", Long.class, id))
                        .as("hệ thống hủy: handled_by NULL").isNull();
                assertThat(jdbc.queryForObject("SELECT result FROM care_tasks WHERE id = ?", String.class, id)).isNull();
            } else {
                assertThat(xmin(id)).as("task " + id + " không được UPDATE").isEqualTo(before.get(id).xmin());
                assertThat(statusOf(id)).isEqualTo(before.get(id).status());
            }
        });
    }

    @Test
    void revaccinationCancelsOnlyTheOpenVaccineTasksOfTheGivenDoses() {
        Map<Long, Boolean> expectCancel = new LinkedHashMap<>();
        List<Long> passed = new ArrayList<>();
        for (String status : STATUSES) {
            long dose = data.vaccination(pet);
            passed.add(dose);
            for (String type : List.of("VACCINE_DUE", "VACCINE_OVERDUE")) {
                expectCancel.put(data.task(type, dose, branch, customer, pet, status), status.equals("OPEN"));
            }
        }
        long other = data.vaccination(pet);
        for (String type : List.of("VACCINE_DUE", "VACCINE_OVERDUE")) {
            expectCancel.put(data.task(type, other, branch, customer, pet, "OPEN"), false);   // mũi không được truyền
        }
        expectCancel.put(data.task("FOLLOW_UP_DUE", medicalRecord, branch, customer, pet, "OPEN"), false);   // khác loại
        expectCancel.put(data.task("PICKUP_OVERDUE", booking, branch, customer, pet, "OPEN"), false);        // khác loại
        Map<Long, Before> before = snapshot(expectCancel.keySet());

        clock.set(T1);
        tx.executeWithoutResult(status -> api.cancelForRevaccination(passed));

        assertMatrix(expectCancel, before, "Đã tiêm lại (BR-TB-03)");
    }

    @Test
    void followUpCancellationCancelsOnlyTheOpenFollowUpTasksOfTheGivenRecords() {
        Map<Long, Boolean> expectCancel = new LinkedHashMap<>();
        List<Long> passed = new ArrayList<>();
        for (String status : STATUSES) {
            long record = data.medicalRecord(branch, customer, pet);
            passed.add(record);
            expectCancel.put(data.task("FOLLOW_UP_DUE", record, branch, customer, pet, status), status.equals("OPEN"));
        }
        long other = data.medicalRecord(branch, customer, pet);
        expectCancel.put(data.task("FOLLOW_UP_DUE", other, branch, customer, pet, "OPEN"), false);         // bệnh án khác
        expectCancel.put(data.task("VACCINE_DUE", vaccination, branch, customer, pet, "OPEN"), false);      // khác loại
        expectCancel.put(data.task("VACCINE_OVERDUE", vaccination, branch, customer, pet, "OPEN"), false);  // khác loại
        expectCancel.put(data.task("PICKUP_OVERDUE", booking, branch, customer, pet, "OPEN"), false);       // khác loại
        Map<Long, Before> before = snapshot(expectCancel.keySet());

        clock.set(T1);
        tx.executeWithoutResult(status -> api.cancelFollowUpTasks(passed));

        assertMatrix(expectCancel, before, "Đã có lịch khám / lượt khám mới (BR-TB-06)");
    }

    @Test
    void anEmptySetChangesNothing() {
        long open = data.task("VACCINE_DUE", vaccination, branch, customer, pet, "OPEN");
        long follow = data.task("FOLLOW_UP_DUE", medicalRecord, branch, customer, pet, "OPEN");
        Map<Long, Before> before = snapshot(List.of(open, follow));

        tx.executeWithoutResult(status -> {
            api.cancelForRevaccination(List.of());
            api.cancelFollowUpTasks(List.of());
        });

        assertThat(snapshot(List.of(open, follow))).isEqualTo(before);
    }

    @Test
    void aCancellationIsRolledBackWithTheCaller() {
        long open = data.task("VACCINE_DUE", vaccination, branch, customer, pet, "OPEN");
        Map<Long, Before> before = snapshot(List.of(open));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            api.cancelForRevaccination(List.of(vaccination));
            throw new IllegalStateException("bên gọi lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(List.of(open))).isEqualTo(before);
    }

    // ================================================================ đồng thời + chỉ mục

    @Test
    void twoCallersCreatingTheSameOverdueTaskShareOneRow() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = pool.submit(() -> tx.execute(status -> {
                Long id = api.create(task(CareTaskType.VACCINE_OVERDUE, vaccination));
                held.countDown();
                await(release);
                return id;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Long> second = pool.submit(() -> tx.execute(status ->
                    api.create(task(CareTaskType.VACCINE_OVERDUE, vaccination))));
            awaitInsertWaiting();
            assertThat(second.isDone()).as("giao dịch thứ hai phải chờ INSERT của giao dịch thứ nhất").isFalse();

            release.countDown();
            Long firstId = first.get(30, TimeUnit.SECONDS);
            Long secondId = second.get(30, TimeUnit.SECONDS);

            assertThat(secondId).isEqualTo(firstId);
            assertThat(countBy("vaccination_id", vaccination)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void theLookupAfterTheInsertUsesTheUniqueIndexes() {
        String vaccinePlan = explain("SELECT id FROM care_tasks WHERE vaccination_id = 1 AND task_type = 'VACCINE_OVERDUE'");
        String pickupPlan = explain(
                "SELECT id FROM care_tasks WHERE boarding_booking_id = 1 AND task_type = 'PICKUP_OVERDUE'");

        assertThat(vaccinePlan).contains("uq_care_tasks_vaccine_overdue");
        assertThat(pickupPlan).contains("uq_care_tasks_pickup_overdue");
    }

    /**
     * V22 (docs/adr/0081, trả D049): hai câu hủy của hệ thống và câu kiểm FK khi xóa mũi tiêm ghi nhầm
     * ({@code WHERE vaccination_id = $1}, không có {@code task_type}) đều dùng index theo cột nguồn.
     */
    @Test
    void theSystemCancelStatementsAndTheForeignKeyCheckUseTheSourceIndexes() {
        String cancelVaccine = explain("UPDATE care_tasks SET status = 'CANCELLED' WHERE status = 'OPEN' "
                + "AND task_type IN ('VACCINE_DUE', 'VACCINE_OVERDUE') AND vaccination_id IN (1, 2)");
        String cancelFollowUp = explain("UPDATE care_tasks SET status = 'CANCELLED' WHERE status = 'OPEN' "
                + "AND task_type = 'FOLLOW_UP_DUE' AND medical_record_id IN (1)");
        String foreignKeyCheck = explain("SELECT 1 FROM care_tasks WHERE vaccination_id = 1");

        assertThat(cancelVaccine).contains("ix_care_tasks_vaccination_id");
        assertThat(cancelFollowUp).contains("ix_care_tasks_medical_record_id");
        assertThat(foreignKeyCheck).contains("ix_care_tasks_vaccination_id");
    }

    /** Bảng gần như rỗng nên planner chọn Seq Scan; tắt trong transaction để chứng minh index dùng được. */
    private String explain(String sql) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        });
    }

    private void awaitInsertWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%INSERT INTO care_tasks%'""", Long.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy INSERT care_tasks chờ khóa");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
