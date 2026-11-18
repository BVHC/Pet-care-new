package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.support.MutableClock;

/**
 * Đồng thời trên phân ca (workforce-v1 #5; docs/adr/0123 mục 8, docs/adr/0138 bước 3). Cách giữ một request "đứng giữa
 * chừng" khi đang giữ khóa: trigger tạm gọi {@code pg_advisory_xact_lock}, còn test giữ advisory lock đó ở một
 * transaction khác, nên request dừng đúng lúc đã ghi dòng (giữ khóa dòng, chưa commit) — khuôn {@code
 * ShiftDefinitionLockIT}. Chứng minh: (1) hai phân ca chồng giờ của cùng một người chỉ một cái lọt; (2) khóa
 * {@code accounts} xung đột với điều chuyển / vô hiệu hóa và với {@code findStaffForShare} (visit, thu ngân) nhưng các
 * luồng đó chờ rồi chạy tiếp; (3) điều chuyển commit trong lúc chờ → 404; (4) ngừng dùng ca trong lúc chờ → 400; (5) hai
 * nhân viên khác nhau không chặn nhau. Era <b>năm 2051</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentLockIT.TestBeans.class})
class ShiftAssignmentLockIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2051-03-14T01:00:00Z");
    private static final String DAY = "2051-03-21";
    private static final long PAUSE_KEY = 20_510_001L;
    private static final long WAIT_SECONDS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private StaffDirectoryApi staffDirectory;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private long branch;
    private long manager;
    private long staff;
    private long morning;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sa_pause ON shift_assignments");
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sa_pause ON shift_definitions");
            jdbc.execute("DROP FUNCTION IF EXISTS sa_test_pause()");
            pool.shutdownNow();
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) cùng một nhân viên: tuần tự hóa

    @Test
    void twoOverlappingAssignmentsForTheSameStaffMemberLeaveExactlyOneAndTheOtherReadsItAfterTheWait() throws Exception {
        long midday = definition(branch, "Ca trưa", "10:00", "14:00", true);
        pauseAfter("shift_assignments", "INSERT", "NEW.staff_id = " + staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, midday, DAY)));
        awaitLockWait("accounts");
        assertThat(second.isDone()).as("bên sau phải chờ khóa tài khoản của bên trước").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> rejected = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(rejected, "BR-NS-02");
        assertThat(message(rejected)).contains("08:00–12:00");
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(1);
        assertThat(assignmentAuditCountOfStaff(staff)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- (2) luồng khác trên cùng nhân viên chờ rồi chạy tiếp

    @Test
    void transferStyleAndSharedLocksOnTheSameAccountWaitForTheAssignmentAndThenProceed() throws Exception {
        pauseAfter("shift_assignments", "INSERT", "NEW.staff_id = " + staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)));
        awaitPausedWriter();

        // Điều chuyển / vô hiệu hóa giữ FOR NO KEY UPDATE trên dòng này; thu ngân và visit lấy FOR SHARE: cả hai bị chặn.
        assertLockTimeout("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE");
        assertLockTimeout("SELECT id FROM accounts WHERE id = ? FOR SHARE");
        Future<Optional<StaffSummary>> visitLike = pool.submit(
                () -> tx.execute(status -> staffDirectory.findStaffForShare(staff)));
        awaitLockWait("accounts");
        assertThat(visitLike.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(assign.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(visitLike.get(WAIT_SECONDS, TimeUnit.SECONDS)).isPresent();
    }

    // ---------------------------------------------------------------- (3) điều chuyển commit trong lúc chờ

    @Test
    void aTransferCommittedWhileTheRequestWaitsMakesItA404AndWritesNothing() throws Exception {
        long newBranch = data.branch();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> transfer = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, staff);
            jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", newBranch, staff);
            locked.countDown();
            await(release);
            return null;
        }));
        assertThat(locked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)));
        awaitLockWait("accounts");
        assertThat(assign.isDone()).as("kiểm sơ bộ thấy chi nhánh cũ nên vào tới bước khóa và chờ").isFalse();

        release.countDown();
        transfer.get(WAIT_SECONDS, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = assign.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(assignmentCountOfStaff(staff)).isZero();
        assertThat(assignmentAuditCountOfStaff(staff)).isZero();
    }

    // ---------------------------------------------------------------- (4) ca bị ngừng dùng trong lúc chờ

    @Test
    void aShiftDeactivatedWhileTheRequestWaitsMakesItA400AndWritesNothing() throws Exception {
        pauseAfter("shift_definitions", "UPDATE", "NEW.id = " + morning);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> deactivate = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + morning, token, "{'active':false}"));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)));
        awaitLockWait("shift_definitions");
        assertThat(assign.isDone()).as("FOR SHARE của phân ca chờ khóa của PATCH").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(deactivate.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> response = assign.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(response, "BR-NS-02");
        assertThat(message(response)).contains("ngừng dùng");
        assertThat(assignmentCountOfStaff(staff)).isZero();
        assertThat(row(morning)).containsEntry("is_active", false);
    }

    // ---------------------------------------------------------------- (5) hai nhân viên khác nhau không chặn nhau

    @Test
    void assignmentsForDifferentStaffMembersDoNotBlockEachOther() throws Exception {
        long other = data.staff("VET", branch);
        pauseAfter("shift_assignments", "INSERT", "NEW.staff_id = " + staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)));
        awaitPausedWriter();
        ResponseEntity<Map<String, Object>> second = pool.submit(
                () -> call(HttpMethod.POST, ASSIGNMENTS, token, body(other, morning, DAY)))
                .get(10, TimeUnit.SECONDS);

        assertThat(second.getStatusCode().value()).as("khóa theo từng nhân viên, không khóa cả chi nhánh").isEqualTo(201);
        assertThat(first.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(assignmentCount(branch)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- hạ tầng giữ request giữa chừng

    private void assertLockTimeout(String sql) {
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '700ms'");
            return jdbc.queryForObject(sql, Long.class, staff);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("lock timeout");
    }

    /** Trigger tạm: sau khi ghi một dòng thỏa {@code condition}, chờ advisory lock {@link #PAUSE_KEY}. */
    private void pauseAfter(String table, String event, String condition) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sa_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sa_pause ON " + table);
        jdbc.execute("CREATE TRIGGER trg_sa_pause AFTER %s ON %s FOR EACH ROW WHEN (%s) "
                .formatted(event, table, condition) + "EXECUTE FUNCTION sa_test_pause()");
    }

    private Future<?> holdPauseLock(CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(" + PAUSE_KEY + ")");
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Đợi tới khi một request đứng chờ advisory lock, tức đã ghi dòng và đang giữ khóa dòng. */
    private void awaitPausedWriter() throws InterruptedException {
        awaitCount("""
                SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = %d AND NOT granted"""
                .formatted(PAUSE_KEY), "request chưa dừng ở trigger tạm");
    }

    /** Đợi tới khi một câu lệnh nhắc tới {@code table} đang chờ khóa dòng của giao dịch khác (không tính advisory). */
    private void awaitLockWait(String table) throws InterruptedException {
        awaitCount("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND wait_event <> 'advisory'
                  AND query ILIKE '%%%s%%' AND pid <> pg_backend_pid()""".formatted(table),
                "chưa có câu lệnh chờ khóa dòng của " + table);
    }

    private void awaitCount(String sql, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject(sql, Integer.class);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(failure);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
