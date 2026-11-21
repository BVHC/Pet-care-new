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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Khóa của ghi vắng đột xuất (workforce-v1 #7; docs/adr/0138 bước 7, ADR-0123 mục 8) trên Postgres thật. Giữ request
 * "giữa chừng" bằng trigger tạm gọi {@code pg_advisory_xact_lock} còn test giữ advisory lock đó ở transaction khác
 * (khuôn {@code ShiftAssignmentCancelLockIT}). Chứng minh: (1) hai lần ghi vắng song song và ghi vắng ∥ hủy (cả hai
 * chiều) chỉ một cái thắng, cái kia đọc lại dưới khóa và nhận 409, chỉ một bộ audit / thông báo; (2) ghi vắng khóa
 * {@code accounts} <b>trước</b> phân ca; (3) nhân viên đổi chi nhánh trong lúc chờ không đổi kết quả; (4) gửi yêu cầu
 * ∥ ghi vắng và rút yêu cầu ∥ ghi vắng: dù ai thắng, phân ca là {@code ABSENT} và <b>không còn yêu cầu PENDING</b>; (5)
 * xong thì mọi khóa đã nhả. Era <b>năm 2071</b>. Phân ca nằm ở ca 14:00 của hôm nay (đồng hồ 08:00) để cả hủy lẫn ghi vắng
 * đều hợp lệ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentAbsenceLockIT.TestBeans.class})
class ShiftAssignmentAbsenceLockIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2071-03-14T01:00:00Z"); // 08:00 giờ Việt Nam
    private static final LocalDate TODAY = LocalDate.of(2071, 3, 14);
    private static final long PAUSE_KEY = 20_710_001L;
    private static final long WAIT_SECONDS = 20;
    private static final String LEAD_KEY = "workforce.request_lead_hours";
    private static final String LEAD_DEFAULT = "24";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private SystemConfigService configService;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private long branch;
    private long manager;
    private long staff;
    private long assignment;
    private long request;
    private String managerToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long afternoon = definition(branch, "Ca chiều", "14:00", "18:00", true);
        assignment = data.assignment(branch, staff, afternoon, TODAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        managerToken = token(manager);
        staffToken = token(staff);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_al_pause ON shift_assignments");
            jdbc.execute("DROP FUNCTION IF EXISTS al_test_pause()");
            pool.shutdownNow();
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", LEAD_DEFAULT, LEAD_KEY);
            configService.reload();
        } finally {
            purgeAbsenceOutbox(staff);
            purgeCancelOutbox(staff);
            jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ?", manager);
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) hai thao tác song song cùng một phân ca

    @Test
    void twoConcurrentAbsencesOfTheSameAssignmentLeaveOneSuccessOne409AndASingleSetOfEffects() throws Exception {
        pauseAfterTheUpdateTo("ABSENT");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(this::absent);
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(this::absent);
        awaitLockWaiters("accounts", 1);
        assertThat(second.isDone()).as("bên sau phải chờ khóa tài khoản của bên trước").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(absenceAuditCount(assignment)).isEqualTo(1);
        assertThat(absenceOutbox(staff)).hasSize(2);
        assertThat(requestRow(request)).containsEntry("status", "REJECTED").containsEntry("decision_note", ABSENCE_NOTE);
        assertLocksReleased();
    }

    @Test
    void anAbsenceRacingACancelWhereTheAbsenceWinsLeavesTheAssignmentAbsentAndTheCancelRejected() throws Exception {
        pauseAfterTheUpdateTo("ABSENT");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaiters("accounts", 1);

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absence.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT");
        assertThat(absenceAuditCount(assignment)).isEqualTo(1);
        assertThat(cancelAuditCount(assignment)).as("hủy thua không để lại audit").isZero();
        assertThat(absenceOutbox(staff)).hasSize(2);
        assertThat(cancelOutbox(staff)).as("hủy thua không để lại thông báo").isEmpty();
        assertLocksReleased();
    }

    @Test
    void aCancelRacingAnAbsenceWhereTheCancelWinsLeavesTheAssignmentCancelledAndTheAbsenceRejected() throws Exception {
        pauseAfterTheUpdateTo("CANCELLED");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        awaitLockWaiters("accounts", 1);

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = absence.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertThat(cancelAuditCount(assignment)).isEqualTo(1);
        assertThat(absenceAuditCount(assignment)).as("ghi vắng thua không để lại audit").isZero();
        assertThat(cancelOutbox(staff)).hasSize(2);
        assertThat(absenceOutbox(staff)).as("ghi vắng thua không để lại thông báo").isEmpty();
        assertThat(requestRow(request)).containsEntry("status", "REJECTED");
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (2) thứ tự khóa: accounts trước phân ca

    @Test
    void absenceWaitsOnTheStaffAccountBeforeTouchingTheAssignmentAndThenProceeds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, false);

        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        awaitLockWaiters("accounts", 1);
        assertThat(absence.isDone()).isFalse();

        // Cùng thứ tự khóa với hủy, phân hộ, duyệt yêu cầu, đăng ký tuần và listener: phân ca và yêu cầu chưa bị khóa.
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absence.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT");
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (3) điều chuyển trong lúc chờ

    @Test
    void aTransferCommittedWhileTheAbsenceWaitsDoesNotChangeTheOutcomeBecauseTheScopeIsTheAssignmentsBranch()
            throws Exception {
        long otherBranch = data.branch();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, true, otherBranch);

        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        awaitLockWaiters("accounts", 1);

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absence.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT").containsEntry("branch_id", branch);
        assertThat(jdbc.queryForObject("SELECT branch_id FROM staff_profiles WHERE account_id = ?", Long.class, staff))
                .isEqualTo(otherBranch);
        assertThat(absenceOutbox(staff)).hasSize(2);
    }

    // ---------------------------------------------------------------- (4) yêu cầu của nhân viên đua với ghi vắng

    @Test
    void aSubmitRacingAnAbsenceNeverLeavesAPendingRequestOnAnAbsentAssignment() throws Exception {
        // Hạn gửi mặc định 24 giờ khiến ca đủ điều kiện ghi vắng không thể có yêu cầu mới; đặt về 0 để cuộc đua có thật.
        jdbc.update("UPDATE system_configs SET value = '0' WHERE key = ?", LEAD_KEY);
        configService.reload();
        jdbc.update("DELETE FROM shift_requests WHERE id = ?", request); // bắt đầu từ phân ca chưa có yêu cầu chờ
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, false);

        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(() -> call(HttpMethod.POST,
                "/api/me/shift-requests", staffToken,
                "{'shiftAssignmentId':" + assignment + ",'type':'LEAVE','reason':'Việc gia đình'}"));
        awaitLockWaiters("accounts", 2);

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absence.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> submitted = submit.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT");
        assertThat(pendingCount()).as("phân ca ABSENT không được còn yêu cầu PENDING").isZero();
        if (submitted.getStatusCode().value() == 201) {
            // Gửi thắng trước: yêu cầu vừa tạo bị Yêu cầu#5 đóng cùng transaction của ghi vắng.
            assertThat(jdbc.queryForObject("""
                    SELECT status || ':' || decision_note FROM shift_requests WHERE assignment_id = ?""", String.class,
                    assignment)).isEqualTo("REJECTED:" + ABSENCE_NOTE);
        } else {
            // Ghi vắng thắng trước: gửi nhận 400 BR-NS-04 (phân ca không còn SCHEDULED) và không tạo dòng nào.
            assertBusinessRule(submitted, "BR-NS-04");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE assignment_id = ?", Integer.class,
                    assignment)).isZero();
        }
        assertLocksReleased();
    }

    @Test
    void aWithdrawRacingAnAbsenceEndsWithTheRequestWithdrawnOrRejectedButNeverStuckPending() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release, false);

        Future<ResponseEntity<Map<String, Object>>> absence = pool.submit(this::absent);
        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(() -> call(HttpMethod.POST,
                "/api/me/shift-requests/" + request + "/withdraw", staffToken));
        awaitLockWaiters("accounts", 2);

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absence.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> withdrawn = withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT");
        assertThat(pendingCount()).isZero();
        String status = String.valueOf(requestRow(request).get("status"));
        if (withdrawn.getStatusCode().value() == 200) {
            assertThat(status).as("rút thắng trước thì yêu cầu ở WITHDRAWN").isEqualTo("WITHDRAWN");
        } else {
            assertThat(withdrawn.getStatusCode().value()).isEqualTo(409);
            assertThat(errorCode(withdrawn)).isEqualTo("INVALID_STATE_TRANSITION");
            assertThat(status).as("ghi vắng thắng trước thì yêu cầu bị hệ thống từ chối").isEqualTo("REJECTED");
            assertThat(requestRow(request)).containsEntry("decision_note", ABSENCE_NOTE);
        }
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> absent() {
        return call(HttpMethod.POST, absentPath(assignment), managerToken, reasonBody("Nhân viên báo ốm"));
    }

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(HttpMethod.POST, cancelPath(assignment), managerToken, reasonBody("Nghỉ lễ"));
    }

    private int pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE assignment_id = ? AND status = 'PENDING'",
                Integer.class, assignment);
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, phân ca và yêu cầu đều lấy được ngay (không bị giữ). */
    private void assertLocksReleased() {
        assertThat(canLockNowait("accounts", staff)).isTrue();
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE id = ?", Integer.class, request) > 0) {
            assertThat(canLockNowait("shift_requests", request)).isTrue();
        }
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

    /** Trigger tạm: sau khi phân ca này được UPDATE sang {@code status}, chờ advisory lock {@link #PAUSE_KEY}. */
    private void pauseAfterTheUpdateTo(String status) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION al_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_al_pause ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_al_pause AFTER UPDATE ON shift_assignments FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.status = '%s') EXECUTE FUNCTION al_test_pause()"""
                .formatted(assignment, status));
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
                .formatted(PAUSE_KEY), 1, "request chưa dừng ở trigger tạm");
    }

    /** Đợi tới khi ít nhất {@code waiters} câu lệnh nhắc tới {@code table} đang chờ khóa dòng (không tính advisory). */
    private void awaitLockWaiters(String table, int waiters) throws InterruptedException {
        awaitCount("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND wait_event <> 'advisory'
                  AND query ILIKE '%%%s%%' AND pid <> pg_backend_pid()""".formatted(table), waiters,
                "chưa có đủ " + waiters + " câu lệnh chờ khóa dòng của " + table);
    }

    private void awaitCount(String sql, int atLeast, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject(sql, Integer.class);
            if (count != null && count >= atLeast) {
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
