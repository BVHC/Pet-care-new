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
 * Khóa của từ chối yêu cầu (workforce-v1 #14; docs/adr/0150, ADR-0123 mục 8) trên Postgres thật. Một transaction khác
 * giữ {@code FOR NO KEY UPDATE} trên dòng tài khoản của nhân viên (như điều chuyển / vô hiệu hóa / hủy phân ca); các
 * request xếp hàng chờ khóa đó <b>theo thứ tự khởi động</b> (hàng đợi khóa của PostgreSQL), nên kết quả của từng cặp
 * xung đột là xác định: (1) hai lần từ chối song song → một 200, một 409, đúng một audit và một cặp thông báo; (2) từ
 * chối ∥ rút (#11) theo cả hai thứ tự; (3) từ chối ∥ hủy phân ca (#6) theo cả hai thứ tự; (4) từ chối ∥ ghi vắng (#7);
 * (5) từ chối ∥ gửi lại (#9) khi yêu cầu cũ đã đóng; (6) khóa {@code accounts} <b>trước</b> phân ca và yêu cầu; (7) xong
 * thì mọi khóa đã nhả. Era <b>năm 2078</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestRejectLockIT.TestBeans.class})
class ShiftRequestRejectLockIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2078-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2078, 3, 21);
    private static final long WAIT_SECONDS = 20;
    private static final String SYSTEM_NOTE_CANCEL = "Phân ca đã bị quản lý hủy";
    private static final String SYSTEM_NOTE_ABSENCE = "Phân ca đã được ghi nhận vắng đột xuất";

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
    private long secondManager;
    private long staff;
    private long assignment;
    private long request;
    private String staffToken;
    private String managerToken;
    private String secondManagerToken;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        secondManager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        staffToken = token(staff);
        managerToken = token(manager);
        secondManagerToken = token(secondManager);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            purgeRejectedOutbox(staff);
            purgeCancelOutbox(staff);
            purgeAbsenceOutbox(staff);
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) hai quản lý cùng từ chối

    @Test
    void twoManagersRejectingTheSameRequestAtOnceLeaveOne200OneConflictAndASingleSetOfEffects() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                () -> reject(secondManagerToken, NOTE_PREFIX + " Lý do của người đến sau"));
        awaitLockWaits(2);
        assertThat(first.isDone() || second.isDone()).as("cả hai phải chờ khóa tài khoản").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
        assertThat(rejectAuditCount(request)).isEqualTo(1);
        assertThat(rejectedOutbox(staff)).hasSize(2);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (2) từ chối ∥ rút

    @Test
    void aRejectQueuedBeforeAWithdrawWinsAndTheWithdrawIs409() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(this::withdraw);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager);
        assertLocksReleased();
    }

    @Test
    void aWithdrawQueuedBeforeARejectWinsAndTheRejectIs409WithoutAuditOrNotice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(this::withdraw);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = reject.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(fullRequestRow(request)).containsEntry("status", "WITHDRAWN").containsEntry("decided_by", null);
        assertThat(rejectAuditCount(request)).isZero();
        assertThat(rejectedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (3) từ chối ∥ hủy phân ca

    @Test
    void aRejectQueuedBeforeACancelKeepsTheManagersNoteAndTheCancelThenFindsNothingToClose() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).as("hủy phân ca không đụng yêu cầu đã từ chối")
                .containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertLocksReleased();
    }

    @Test
    void aCancelQueuedBeforeARejectClosesTheRequestBySystemAndTheRejectIs409() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = reject.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE_CANCEL);
        assertThat(rejectAuditCount(request)).isZero();
        assertThat(rejectedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (4) từ chối ∥ ghi vắng

    @Test
    void anAbsenceQueuedBeforeARejectClosesTheRequestBySystemAndTheRejectIs409() throws Exception {
        long past = data.assignment(branch, staff, definition(branch, "Ca chiều", "13:00", "17:00", true),
                LocalDate.of(2078, 3, 13), "SCHEDULED", "SELF", null);
        long pastRequest = pendingRequest(past, staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> absent = pool.submit(() -> call(HttpMethod.POST, absentPath(past),
                managerToken, reasonBody("Nhân viên báo ốm")));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> call(HttpMethod.POST,
                rejectPath(pastRequest), secondManagerToken, rejectBody(NOTE)));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absent.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
        assertThat(fullRequestRow(pastRequest)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE_ABSENCE);
        assertThat(rejectAuditCount(pastRequest)).isZero();
        purgeAbsenceOutbox(staff);
    }

    // ---------------------------------------------------------------- (5) từ chối ∥ gửi lại

    @Test
    void aSubmitQueuedAfterARejectSeesNoPendingRequestAndCreatesANewOne() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(
                () -> call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment)));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> created = submit.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(created.getStatusCode().value()).as("yêu cầu cũ đã đóng nên không còn yêu cầu chờ").isEqualTo(201);
        assertThat(requestCountOf(assignment)).isEqualTo(2);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED");
        assertLocksReleased();
    }

    @Test
    void aSubmitQueuedBeforeARejectIs400BecauseThePendingRequestIsStillThereAndTheRejectStillWins() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(
                () -> call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment)));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertBusinessRule(submit.get(WAIT_SECONDS, TimeUnit.SECONDS), "BR-NS-04");
        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(requestCountOf(assignment)).isEqualTo(1);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (6) thứ tự khóa: accounts trước phân ca và yêu cầu

    @Test
    void rejectWaitsOnTheStaffAccountBeforeTouchingTheAssignmentOrTheRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(() -> reject(managerToken, NOTE));
        awaitLockWaits(1);
        assertThat(reject.isDone()).isFalse();

        // Cùng thứ tự khóa với hủy phân ca, rút, ghi vắng, đăng ký tuần và listener: phân ca, yêu cầu chưa bị khóa.
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertLocksReleased();
    }

    @Test
    void aRejectOfAnotherBranchsRequestTakesNoLockAtAllSoAHeldAccountDoesNotBlockIt() throws Exception {
        long otherBranch = data.branch();
        long otherManager = data.staff("BRANCH_MANAGER", otherBranch);
        String otherToken = token(otherManager);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        ResponseEntity<Map<String, Object>> denied = call(HttpMethod.POST, rejectPath(request), otherToken,
                rejectBody(NOTE));

        assertThat(denied.getStatusCode().value()).as("403 trả ngay, không chờ khóa của chi nhánh khác").isEqualTo(403);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> reject(String token, String note) {
        return call(HttpMethod.POST, rejectPath(request), token, rejectBody(note));
    }

    private ResponseEntity<Map<String, Object>> withdraw() {
        return call(HttpMethod.POST, withdrawPath(request), staffToken);
    }

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(HttpMethod.POST, cancelPath(assignment), secondManagerToken, reasonBody("Quản lý hủy"));
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, phân ca và yêu cầu lấy được ngay, tức không bị giữ. */
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

    /** Một transaction khác giữ {@code FOR NO KEY UPDATE} trên dòng tài khoản của nhân viên tới khi {@code release}. */
    private Future<?> holdStaffAccount(CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, staff);
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Đợi tới khi đúng {@code count} câu lệnh nhắc tới {@code accounts} đang chờ khóa dòng của giao dịch khác. */
    private void awaitLockWaits(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%accounts%' AND pid <> pg_backend_pid()""",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("chưa có " + count + " câu lệnh chờ khóa dòng của accounts");
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
