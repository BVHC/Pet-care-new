package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Khóa của hủy phân ca (workforce-v1 #6; docs/adr/0149 bước 4, ADR-0123 mục 8) trên Postgres thật. Giữ request "giữa
 * chừng" bằng trigger tạm gọi {@code pg_advisory_xact_lock} còn test giữ advisory lock đó ở transaction khác (khuôn
 * {@code ShiftAssignmentLockIT}). Chứng minh: (1) hai lần hủy song song cùng một phân ca chỉ một cái thành công, cái
 * kia đọc lại dưới khóa và nhận 409, chỉ một audit và một bộ thông báo; (2) hủy khóa {@code accounts} <b>trước</b> phân ca
 * (phân ca và yêu cầu còn khóa được trong lúc chờ) rồi chạy tiếp khi nhân viên được nhả; (3) nhân viên đổi chi nhánh
 * trong lúc chờ không đổi kết quả vì phạm vi theo chi nhánh của phân ca; (4) xong thì mọi khóa đã nhả. Era
 * <b>năm 2055</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentCancelLockIT.TestBeans.class})
class ShiftAssignmentCancelLockIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2055-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2055, 3, 21);
    private static final long PAUSE_KEY = 20_550_001L;
    private static final long WAIT_SECONDS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private long branch;
    private long manager;
    private long staff;
    private long assignment;
    private long request;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_cl_pause ON shift_assignments");
            jdbc.execute("DROP FUNCTION IF EXISTS cl_test_pause()");
            pool.shutdownNow();
        } finally {
            purgeCancelOutbox(staff);
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) hai lần hủy song song

    @Test
    void twoConcurrentCancelsOfTheSameAssignmentLeaveOneSuccessOne409AndASingleSetOfEffects() throws Exception {
        pauseAfterTheCancelUpdate();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(this::cancel);
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(this::cancel);
        awaitLockWait("accounts");
        assertThat(second.isDone()).as("bên sau phải chờ khóa tài khoản của bên trước").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(cancelAuditCount(assignment)).isEqualTo(1);
        assertThat(cancelOutbox(staff)).hasSize(2);
        assertThat(requestRow(request)).containsEntry("status", "REJECTED");
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (2) thứ tự khóa: accounts trước phân ca

    @Test
    void cancelWaitsOnTheStaffAccountBeforeTouchingTheAssignmentAndThenProceeds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, false);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWait("accounts");
        assertThat(cancel.isDone()).isFalse();

        // Cùng thứ tự khóa với phân hộ, duyệt yêu cầu, đăng ký tuần và listener: phân ca và yêu cầu chưa bị khóa.
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (3) điều chuyển trong lúc chờ

    @Test
    void aTransferCommittedWhileTheCancelWaitsDoesNotChangeTheOutcomeBecauseTheScopeIsTheAssignmentsBranch()
            throws Exception {
        long otherBranch = data.branch();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, true, otherBranch);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWait("accounts");

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED").containsEntry("branch_id", branch);
        assertThat(jdbc.queryForObject("SELECT branch_id FROM staff_profiles WHERE account_id = ?", Long.class, staff))
                .isEqualTo(otherBranch);
        assertThat(cancelOutbox(staff)).hasSize(2);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(HttpMethod.POST, cancelPath(assignment), token, reasonBody("Nghỉ lễ"));
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, phân ca và yêu cầu đều lấy được ngay (không bị giữ). */
    private void assertLocksReleased() {
        assertThat(canLockNowait("accounts", staff)).isTrue();
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();
    }

    private boolean canLockNowait(String table, long id) {
        try {
            tx.execute(status -> jdbc.queryForObject(
                    "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", Long.class, id));
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    /**
     * Một transaction khác giữ {@code FOR NO KEY UPDATE} trên dòng tài khoản của nhân viên (như điều chuyển / vô hiệu hóa)
     * tới khi {@code release}; {@code transfer} = đổi luôn chi nhánh rồi commit khi nhả.
     */
    private Future<?> holdStaffAccount(CountDownLatch release, boolean transfer, long... newBranch)
            throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, staff);
            if (transfer) {
                jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", newBranch[0], staff);
            }
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Trigger tạm: sau khi phân ca này được UPDATE sang {@code CANCELLED}, chờ advisory lock {@link #PAUSE_KEY}. */
    private void pauseAfterTheCancelUpdate() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION cl_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_cl_pause ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_cl_pause AFTER UPDATE ON shift_assignments FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.status = 'CANCELLED') EXECUTE FUNCTION cl_test_pause()"""
                .formatted(assignment));
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
