package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * Khóa của chuỗi "nhân viên rời chi nhánh" (BR-NS-09; docs/adr/0138 bước 5, ADR-0123 mục 8) trên Postgres thật. Giữ
 * request của identity "giữa chừng" — <b>sau khi đã khóa {@code accounts} và đã UPDATE phân ca</b> — bằng trigger tạm gọi
 * {@code pg_advisory_xact_lock} còn test giữ advisory lock đó ở transaction khác (khuôn
 * {@code ShiftAssignmentCancelLockIT}). Chứng minh thứ tự khóa {@code accounts → phân ca → yêu cầu} không bị đảo: (1) hủy
 * tay #6 đến trong lúc đó chờ khóa {@code accounts} rồi đọc lại và nhận 409, không hủy đôi, không thông báo đôi; (2) phân
 * hộ #5 đến trong lúc điều chuyển thấy chi nhánh mới → 404 và không ghi gì; (3) phân hộ #5 đến trong lúc vô hiệu hóa
 * thấy nhân viên không còn hoạt động → 400 {@code BR-NS-02}; (4) xong thì mọi khóa đã nhả. Era <b>năm 2060</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffLeftBranchLockIT.TestBeans.class})
class StaffLeftBranchLockIT extends StaffLeftBranchItSupport {

    private static final Instant NOW = Instant.parse("2060-03-14T03:00:00Z"); // 10:00 giờ Việt Nam
    private static final long PAUSE_KEY = 20_600_001L;
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
    private long managerA;
    private String superToken;
    private String managerToken;
    private int assignmentsBefore;

    @BeforeEach
    void setUp() {
        seedWorld(NOW);
        managerA = data.staff("BRANCH_MANAGER", branchA);
        superToken = token(superManager);
        managerToken = token(managerA);
        assignmentsBefore = assignmentCountOfStaff(staff);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sll_pause ON shift_assignments");
            jdbc.execute("DROP FUNCTION IF EXISTS sll_test_pause()");
            pool.shutdownNow();
        } finally {
            tearDownWorld();
        }
    }

    // ---------------------------------------------------------------- (1) hủy tay đến trong lúc vô hiệu hóa

    @Test
    void aManualCancelArrivingDuringTheDisableWaitsOnTheAccountThenGets409AndNothingIsDoneTwice() throws Exception {
        pauseAfterTheLeavingUpdate();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> disabling = pool.submit(() -> disable(superToken, staff));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> manual = pool.submit(this::cancelByManagerTheAfternoon);
        awaitLockWait("accounts");
        assertThat(manual.isDone()).as("hủy tay phải chờ khóa tài khoản mà vô hiệu hóa đang giữ").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(disabling.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = manual.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(cancelAuditCount(futureAfternoon)).as("hủy tay không ghi audit").isZero();
        assertThat(cancelOutbox(staff)).as("chỉ ba cặp của việc rời chi nhánh, không có cặp của hủy tay").hasSize(6);
        assertThat(assignmentRow(futureAfternoon)).containsEntry("reason", LEFT_REASON)
                .containsEntry("status_changed_by", null);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (2) phân hộ đến trong lúc điều chuyển

    @Test
    void aManagerAssignmentArrivingDuringTheTransferSeesTheNewBranchAndGets404WithoutWritingAnything()
            throws Exception {
        pauseAfterTheLeavingUpdate();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> transferring = pool.submit(() -> transfer(superToken, staff, branchB));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(this::assignAFreeDay);
        awaitLockWait("accounts");
        assertThat(assign.isDone()).as("kiểm sơ bộ còn thấy chi nhánh cũ nên vào tới bước khóa và chờ").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(transferring.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> notFound = assign.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(assignmentCountOfStaff(staff)).as("không phân ca mới nào lọt vào").isEqualTo(assignmentsBefore);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (3) phân hộ đến trong lúc vô hiệu hóa

    @Test
    void aManagerAssignmentArrivingDuringTheDisableSeesAnInactiveStaffMemberAndGets400() throws Exception {
        pauseAfterTheLeavingUpdate();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> disabling = pool.submit(() -> disable(superToken, staff));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(this::assignAFreeDay);
        awaitLockWait("accounts");
        assertThat(assign.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(disabling.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertBusinessRule(assign.get(WAIT_SECONDS, TimeUnit.SECONDS), "BR-NS-02");
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(assignmentsBefore);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> cancelByManagerTheAfternoon() {
        return call(HttpMethod.POST, cancelPath(futureAfternoon), managerToken, reasonBody("Nghỉ lễ"));
    }

    private ResponseEntity<Map<String, Object>> assignAFreeDay() {
        return call(HttpMethod.POST, ASSIGNMENTS, managerToken, body(staff, afternoonA, today.plusDays(30).toString()));
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, mọi phân ca của nhân viên và yêu cầu đều lấy được ngay. */
    private void assertLocksReleased() {
        assertThat(canLockNowait("accounts", staff)).isTrue();
        for (long id : assignmentIdsOfStaff(staff)) {
            assertThat(canLockNowait("shift_assignments", id)).as("phân ca %d", id).isTrue();
        }
        assertThat(canLockNowait("shift_requests", requestOnFuture)).isTrue();
        assertThat(canLockNowait("shift_requests", requestOnStarted)).isTrue();
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
     * Trigger tạm: sau khi phân ca {@code futureMorning} (phân ca đầu theo id, nên là dòng đầu tiên listener UPDATE) sang
     * {@code CANCELLED}, chờ advisory lock {@link #PAUSE_KEY}: lúc đó identity đang giữ {@code accounts} và listener đã
     * khóa mọi phân ca cần hủy.
     */
    private void pauseAfterTheLeavingUpdate() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sll_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sll_pause ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_sll_pause AFTER UPDATE ON shift_assignments FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.status = 'CANCELLED') EXECUTE FUNCTION sll_test_pause()"""
                .formatted(futureMorning));
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

    /** Đợi tới khi một request đứng chờ advisory lock, tức đã khóa {@code accounts}, UPDATE phân ca và đang giữ khóa dòng. */
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
