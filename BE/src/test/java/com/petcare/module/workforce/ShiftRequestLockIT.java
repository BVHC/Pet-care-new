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
 * Khóa của gửi / rút yêu cầu nghỉ / đổi ca (workforce-v1 #9, #11; docs/adr/0138 bước 6, ADR-0123 mục 8) trên Postgres
 * thật. Một transaction khác giữ {@code FOR NO KEY UPDATE} trên dòng tài khoản của nhân viên (như điều chuyển / vô hiệu
 * hóa / hủy phân ca); các request xếp hàng chờ khóa đó <b>theo thứ tự khởi động</b> (hàng đợi khóa của PostgreSQL), nên
 * kết quả của từng cặp xung đột là xác định: (1) hai lần gửi song song cùng một phân ca → một 201, một 400
 * {@code BR-NS-04}, đúng một yêu cầu và một bộ thông báo; (2) rút ∥ hủy phân ca (#6) theo cả hai thứ tự; (3) gửi ∥ hủy phân
 * ca theo cả hai thứ tự; (4) khóa {@code accounts} <b>trước</b> phân ca và yêu cầu; (5) xong thì mọi khóa đã nhả. Era
 * <b>năm 2067</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestLockIT.TestBeans.class})
class ShiftRequestLockIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2067-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2067, 3, 21);
    private static final long WAIT_SECONDS = 20;
    private static final String SYSTEM_NOTE = "Phân ca đã bị quản lý hủy";

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
    private String staffToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        staffToken = token(staff);
        managerToken = token(manager);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            purgeSubmittedOutbox();
            purgeCancelOutbox(staff);
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) hai lần gửi song song

    @Test
    void twoConcurrentSubmitsOfTheSameAssignmentLeaveOneCreatedOneBrNs04AndASingleSetOfEffects() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(this::submit);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(this::submit);
        awaitLockWaits(2);
        assertThat(first.isDone() || second.isDone()).as("cả hai phải chờ khóa tài khoản").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> rejected = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(rejected, "BR-NS-04");
        assertThat(message(rejected)).contains("đã có yêu cầu đang chờ");
        assertThat(requestCountOf(assignment)).isEqualTo(1);
        assertThat(submittedOutbox(manager)).hasSize(1);
        assertLocksReleased(null);
    }

    // ---------------------------------------------------------------- (2) rút ∥ hủy phân ca

    @Test
    void aWithdrawQueuedBeforeAManagersCancelWinsAndTheCancelThenFindsNothingToClose() throws Exception {
        long request = pendingRequest(assignment, staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(() -> withdraw(request));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).as("hủy phân ca không đụng yêu cầu đã rút").containsEntry("status",
                "WITHDRAWN");
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertLocksReleased(request);
    }

    @Test
    void aManagersCancelQueuedBeforeAWithdrawClosesTheRequestAndTheWithdrawIs409() throws Exception {
        long request = pendingRequest(assignment, staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(() -> withdraw(request));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE);
        assertLocksReleased(request);
    }

    // ---------------------------------------------------------------- (3) gửi ∥ hủy phân ca

    @Test
    void aSubmitQueuedBeforeACancelIsClosedBySystemRightAfterItIsCreated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(this::submit);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(submit.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        long request = onlyRequestIdOf(assignment);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decision_note",
                SYSTEM_NOTE);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertThat(submittedOutbox(manager)).as("thông báo của lần gửi đã commit").hasSize(1);
        assertLocksReleased(request);
    }

    @Test
    void aCancelQueuedBeforeASubmitMakesTheSubmitA400BecauseTheAssignmentIsNoLongerScheduled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(this::submit);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = submit.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(rejected, "BR-NS-04");
        assertThat(message(rejected)).contains("phân ca đang chờ");
        assertThat(requestCountOf(assignment)).isZero();
        assertThat(submittedOutboxCount()).isZero();
        assertLocksReleased(null);
    }

    // ---------------------------------------------------------------- (4) thứ tự khóa: accounts trước phân ca và yêu cầu

    @Test
    void submitAndWithdrawWaitOnTheStaffAccountBeforeTouchingTheAssignmentOrTheRequest() throws Exception {
        long request = pendingRequest(assignment, staff);
        long other = data.assignment(branch, staff, definition(branch, "Ca chiều", "13:00", "17:00", true), DAY,
                "SCHEDULED", "SELF", null);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(() -> call(HttpMethod.POST, REQUESTS,
                staffToken, leaveBody(other)));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(() -> withdraw(request));
        awaitLockWaits(2);
        assertThat(submit.isDone() || withdraw.isDone()).isFalse();

        // Cùng thứ tự khóa với hủy phân ca, duyệt yêu cầu, đăng ký tuần và listener: phân ca và yêu cầu chưa bị khóa.
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_assignments", other)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(submit.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertLocksReleased(request);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> submit() {
        return call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment));
    }

    private ResponseEntity<Map<String, Object>> withdraw(long requestId) {
        return call(HttpMethod.POST, withdrawPath(requestId), staffToken);
    }

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(HttpMethod.POST, cancelPath(assignment), managerToken, reasonBody("Quản lý hủy"));
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, phân ca và (nếu có) yêu cầu lấy được ngay, tức không bị giữ. */
    private void assertLocksReleased(Long request) {
        assertThat(canLockNowait("accounts", staff)).isTrue();
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        if (request != null) {
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
