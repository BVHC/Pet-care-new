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
 * Khóa của duyệt yêu cầu nghỉ (workforce-v1 #13; docs/adr/0139, ADR-0123 mục 8) trên Postgres thật. Một transaction khác
 * giữ {@code FOR NO KEY UPDATE} trên dòng tài khoản của nhân viên; các request xếp hàng chờ khóa đó <b>theo thứ tự khởi
 * động</b>, nên kết quả của từng cặp xung đột là xác định, theo cả hai thứ tự: (1) hai quản lý cùng duyệt; (2) duyệt ∥
 * từ chối; (3) duyệt ∥ rút (#11); (4) duyệt ∥ hủy phân ca (#6); (5) duyệt ∥ ghi vắng (#7); (6) duyệt ∥ gửi lại (#9);
 * (7) khóa {@code accounts} <b>trước</b> phân ca và yêu cầu, và yêu cầu của chi nhánh khác không khóa gì; (8) duyệt ∥ vô
 * hiệu hóa nhân viên (listener BR-NS-09), giữ identity "giữa chừng" bằng trigger tạm gọi {@code pg_advisory_xact_lock};
 * {@code SWAP} (N3-S5b, ADR-0139 mục 18): (9) thứ tự {@code accounts} → ca đích {@code FOR SHARE} → phân ca; (10) duyệt ∥
 * ngừng dùng ca đích (PATCH #3) theo cả hai thứ tự; (11) duyệt ∥ phân hộ #5 chồng giờ ca đích theo cả hai thứ tự.
 * Bên thua luôn nhận 409 (hoặc 400 của gửi lại) và không để lại dấu vết; bên thắng để lại đúng một bộ hiệu ứng. Era
 * <b>năm 2081</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestApproveLockIT.TestBeans.class})
class ShiftRequestApproveLockIT extends ShiftRequestItSupport {

    /** 10:00 giờ Việt Nam: ca chiều 13:00 của hôm nay chưa bắt đầu (duyệt được) và đã tới ngày (ghi vắng được). */
    private static final Instant NOW = Instant.parse("2081-03-14T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2081, 3, 14);
    private static final LocalDate DAY = LocalDate.of(2081, 3, 21);
    private static final long WAIT_SECONDS = 20;
    private static final long PAUSE_KEY = 20_810_001L;
    private static final String SYSTEM_NOTE_CANCEL = "Phân ca đã bị quản lý hủy";
    private static final String SYSTEM_NOTE_ABSENCE = "Phân ca đã được ghi nhận vắng đột xuất";
    private static final String SYSTEM_NOTE_LEFT = "Nhân viên không còn làm việc tại chi nhánh";

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
    private long morning;
    private long target;
    private long swapStaff;
    private long swapAssignment;
    private long swapRequest;
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
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        // SWAP (N3-S5b): nhân viên riêng (để vô hiệu hóa `staff` ở nhóm (8) không đụng phân ca này), ca sáng → ca đích
        // 13:00–17:00; ca đích chưa có phân ca nào.
        swapStaff = data.staff("RECEPTIONIST", branch);
        target = definition(branch, "Ca đích", "13:00", "17:00", true);
        swapAssignment = data.assignment(branch, swapStaff, morning, DAY.plusDays(1), "SCHEDULED", "SELF", null);
        swapRequest = data.swapRequest(swapAssignment, swapStaff, target, "PENDING", null);
        staffToken = token(staff);
        managerToken = token(manager);
        secondManagerToken = token(secondManager);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sal_pause ON shift_assignments");
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sal_pause ON shift_definitions");
            jdbc.execute("DROP FUNCTION IF EXISTS sal_test_pause()");
            pool.shutdownNow();
        } finally {
            purgeApprovedOutbox(staff, swapStaff);
            purgeRejectedOutbox(staff);
            purgeCancelOutbox(staff);
            purgeAbsenceOutbox(staff);
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- (1) hai quản lý cùng duyệt

    @Test
    void twoManagersApprovingTheSameRequestAtOnceLeaveOne200OneConflictAndASingleSetOfEffects() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(() -> approve(secondManagerToken));
        awaitLockWaits(2);
        assertThat(first.isDone() || second.isDone()).as("cả hai phải chờ khóa tài khoản").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE")
                .containsEntry("status_changed_by", manager);
        assertThat(approveRequestAuditCount(request)).isEqualTo(1);
        assertThat(onLeaveAuditCount(assignment)).isEqualTo(1);
        assertThat(approvedOutbox(staff)).hasSize(2);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (2) duyệt ∥ từ chối

    @Test
    void anApproveQueuedBeforeARejectWinsAndTheRejectIs409WithoutAuditOrNotice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(this::reject);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = reject.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(rejected)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", null);
        assertThat(rejectAuditCount(request)).isZero();
        assertThat(rejectedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    @Test
    void aRejectQueuedBeforeAnApproveWinsAndTheApproveIs409LeavingTheAssignmentScheduledAndNoTrace() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> reject = pool.submit(this::reject);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(secondManagerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(reject.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = approve.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
        assertThat(assignmentRow(assignment)).containsEntry("status", "SCHEDULED");
        assertThat(approveRequestAuditCount(request)).isZero();
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (3) duyệt ∥ rút

    @Test
    void anApproveQueuedBeforeAWithdrawWinsAndTheWithdrawIs409() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(this::withdraw);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE");
        assertLocksReleased();
    }

    @Test
    void aWithdrawQueuedBeforeAnApproveWinsAndTheApproveIs409LeavingTheAssignmentScheduled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> withdraw = pool.submit(this::withdraw);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(withdraw.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
        assertThat(fullRequestRow(request)).containsEntry("status", "WITHDRAWN").containsEntry("decided_by", null);
        assertThat(assignmentRow(assignment)).containsEntry("status", "SCHEDULED");
        assertThat(approveRequestAuditCount(request)).isZero();
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (4) duyệt ∥ hủy phân ca (#6)

    @Test
    void anApproveQueuedBeforeACancelKeepsTheAssignmentOnLeaveAndTheCancelIs409WithoutAuditOrNotice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE");
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(cancelAuditCount(assignment)).isZero();
        assertThat(cancelOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    @Test
    void aCancelQueuedBeforeAnApproveClosesTheRequestBySystemAndTheApproveIs409() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(this::cancel);
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(cancel.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = approve.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE_CANCEL);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertThat(approveRequestAuditCount(request)).isZero();
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (5) duyệt ∥ ghi vắng (#7)

    @Test
    void anApproveQueuedBeforeAnAbsenceKeepsTheAssignmentOnLeaveAndTheAbsenceIs409() throws Exception {
        long today = todayAssignment();
        long todayRequest = pendingRequest(today, staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> call(HttpMethod.POST,
                approvePath(todayRequest), managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> absent = pool.submit(() -> call(HttpMethod.POST, absentPath(today),
                secondManagerToken, reasonBody("Nhân viên báo ốm")));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = absent.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(assignmentRow(today)).containsEntry("status", "ON_LEAVE");
        assertThat(fullRequestRow(todayRequest)).containsEntry("status", "APPROVED");
        assertThat(absenceAuditCount(today)).isZero();
        assertThat(absenceOutbox(staff)).isEmpty();
    }

    @Test
    void anAbsenceQueuedBeforeAnApproveClosesTheRequestBySystemAndTheApproveIs409() throws Exception {
        long today = todayAssignment();
        long todayRequest = pendingRequest(today, staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> absent = pool.submit(() -> call(HttpMethod.POST, absentPath(today),
                secondManagerToken, reasonBody("Nhân viên báo ốm")));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> call(HttpMethod.POST,
                approvePath(todayRequest), managerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(absent.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
        assertThat(assignmentRow(today)).containsEntry("status", "ABSENT");
        assertThat(fullRequestRow(todayRequest)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE_ABSENCE);
        assertThat(onLeaveAuditCount(today)).isZero();
        assertThat(approveRequestAuditCount(todayRequest)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
    }

    // ---------------------------------------------------------------- (6) duyệt ∥ gửi lại

    @Test
    void aSubmitQueuedAfterAnApproveIs400BecauseTheAssignmentIsNoLongerScheduledAndNoNewRequestIsCreated()
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(
                () -> call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment)));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertBusinessRule(submit.get(WAIT_SECONDS, TimeUnit.SECONDS), "BR-NS-04");
        assertThat(requestCountOf(assignment)).as("không có yêu cầu mới trên phân ca đã nghỉ").isEqualTo(1);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE");
        assertLocksReleased();
    }

    @Test
    void aSubmitQueuedBeforeAnApproveIs400BecauseThePendingRequestIsStillThereAndTheApproveStillWins()
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> submit = pool.submit(
                () -> call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment)));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertBusinessRule(submit.get(WAIT_SECONDS, TimeUnit.SECONDS), "BR-NS-04");
        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(requestCountOf(assignment)).isEqualTo(1);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (7) thứ tự khóa

    @Test
    void approveWaitsOnTheStaffAccountBeforeTouchingTheAssignmentOrTheRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWaits(1);
        assertThat(approve.isDone()).isFalse();

        // Cùng thứ tự khóa với hủy phân ca, rút, ghi vắng, từ chối, đăng ký tuần và listener: phân ca, yêu cầu chưa bị khóa.
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertLocksReleased();
    }

    @Test
    void anApproveOfAnotherBranchsRequestTakesNoLockAtAllSoAHeldAccountDoesNotBlockIt() throws Exception {
        long otherBranch = data.branch();
        long otherManager = data.staff("BRANCH_MANAGER", otherBranch);
        String otherToken = token(otherManager);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        ResponseEntity<Map<String, Object>> denied = approve(otherToken);

        assertThat(denied.getStatusCode().value()).as("403 trả ngay, không chờ khóa của chi nhánh khác").isEqualTo(403);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
    }

    // ---------------------------------------------------------------- (8) duyệt ∥ nhân viên rời chi nhánh (BR-NS-09)

    @Test
    void aDisableThatFreezesMidTransactionMakesAnArrivingApproveWaitThenGet409BecauseTheSystemClosedTheRequest()
            throws Exception {
        long superManager = data.staff("SUPER_MANAGER", null);
        pauseAfter("CANCELLED");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> pauseHolder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> disabling = pool.submit(() -> call(HttpMethod.POST,
                "/api/staff/" + staff + "/disable", token(superManager)));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitLockWait("accounts");
        assertThat(approve.isDone()).as("duyệt phải chờ khóa tài khoản mà vô hiệu hóa đang giữ").isFalse();

        release.countDown();
        pauseHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(disabling.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = approve.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", SYSTEM_NOTE_LEFT);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED").containsEntry("reason",
                SYSTEM_NOTE_LEFT);
        assertThat(approveRequestAuditCount(request)).isZero();
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).as("không có thông báo duyệt, chỉ cặp của việc rời chi nhánh").isEmpty();
        assertThat(cancelOutbox(staff)).hasSize(2);
        assertLocksReleased();
    }

    @Test
    void anApproveThatFreezesMidTransactionMakesAnArrivingDisableWaitThenLeaveTheOnLeaveAssignmentAndTheApprovalAlone()
            throws Exception {
        long superManager = data.staff("SUPER_MANAGER", null);
        pauseAfter("ON_LEAVE");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> pauseHolder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approve(managerToken));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> disabling = pool.submit(() -> call(HttpMethod.POST,
                "/api/staff/" + staff + "/disable", token(superManager)));
        awaitLockWait("accounts");
        assertThat(disabling.isDone()).as("vô hiệu hóa phải chờ khóa tài khoản mà duyệt đang giữ").isFalse();

        release.countDown();
        pauseHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(disabling.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).as("BR-NS-09 chỉ hủy phân ca SCHEDULED").containsEntry("status", "ON_LEAVE")
                .containsEntry("status_changed_by", manager);
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(approveRequestAuditCount(request)).isEqualTo(1);
        assertThat(onLeaveAuditCount(assignment)).isEqualTo(1);
        assertThat(approvedOutbox(staff)).hasSize(2);
        assertThat(cancelOutbox(staff)).as("không có cặp thông báo hủy cho phân ca đã nghỉ").isEmpty();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (9) SWAP: thứ tự khóa accounts → ca đích → phân ca

    @Test
    void aSwapApprovalLocksTheAccountFirstAndTheTargetShiftBeforeTheAssignmentAndTheRequest() throws Exception {
        CountDownLatch releaseAccount = new CountDownLatch(1);
        Future<?> accountHolder = holdRow("accounts", swapStaff, releaseAccount);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approveSwap(managerToken));
        awaitLockWaits(1);
        assertThat(canLockNowait("shift_definitions", target)).as("ca đích chưa bị khóa trước accounts").isTrue();
        assertThat(canLockNowait("shift_assignments", swapAssignment)).isTrue();

        // Giữ ca đích như PATCH (FOR NO KEY UPDATE) rồi nhả accounts: duyệt lấy accounts, đứng chờ ở ca đích.
        CountDownLatch releaseTarget = new CountDownLatch(1);
        Future<?> targetHolder = holdRow("shift_definitions", target, releaseTarget);
        releaseAccount.countDown();
        accountHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        awaitLockWait("shift_definitions");
        assertThat(approve.isDone()).isFalse();
        assertThat(canLockNowait("accounts", swapStaff)).as("accounts đã bị duyệt giữ").isFalse();
        assertThat(canLockNowait("shift_assignments", swapAssignment)).as("phân ca chưa bị khóa trước ca đích")
                .isTrue();
        assertThat(canLockNowait("shift_requests", swapRequest)).isTrue();

        releaseTarget.countDown();
        targetHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertSwapLocksReleased();
    }

    // ---------------------------------------------------------------- (10) SWAP ∥ ngừng dùng ca đích (PATCH #3)

    @Test
    void aDeactivationThatFreezesMidTransactionMakesTheSwapApprovalWaitThen400BecauseTheTargetIsNoLongerActive()
            throws Exception {
        pauseDefinitionDeactivation();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> pauseHolder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(this::deactivateTarget);
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approveSwap(managerToken));
        awaitLockWait("shift_definitions");
        assertThat(approve.isDone()).as("duyệt phải chờ khóa ca mà PATCH đang giữ").isFalse();

        release.countDown();
        pauseHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(patch.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> denied = approve.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(denied.getStatusCode().value()).isEqualTo(400);
        assertThat(message(denied)).as("đọc lại is_active dưới khóa").isEqualTo("Ca đích đã ngừng dùng (BR-NS-06)");
        assertThat(assignmentRow(swapAssignment)).containsEntry("shift_definition_id", morning);
        assertThat(fullRequestRow(swapRequest)).containsEntry("status", "PENDING");
        assertThat(swapAuditCount(swapAssignment)).isZero();
        assertSwapLocksReleased();
    }

    @Test
    void aSwapApprovalThatFreezesMidTransactionMakesTheDeactivationWaitThen400BecauseTheTargetNowHasAnAssignment()
            throws Exception {
        pauseAfterSwap();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> pauseHolder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approveSwap(managerToken));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(this::deactivateTarget);
        awaitLockWait("shift_definitions");
        assertThat(patch.isDone()).as("PATCH phải chờ FOR SHARE của duyệt").isFalse();

        release.countDown();
        pauseHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> denied = patch.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(denied.getStatusCode().value()).isEqualTo(400);
        assertThat(message(denied)).endsWith("(BR-NS-01)");
        assertThat(jdbc.queryForObject("SELECT is_active FROM shift_definitions WHERE id = ?", Boolean.class, target))
                .as("không có phân ca SCHEDULED nằm trên ca vừa ngừng dùng").isTrue();
        assertThat(assignmentRow(swapAssignment)).containsEntry("shift_definition_id", target)
                .containsEntry("status", "SCHEDULED");
        assertSwapLocksReleased();
    }

    // ---------------------------------------------------------------- (11) SWAP ∥ phân hộ #5 chồng giờ ca đích

    @Test
    void aSwapApprovalQueuedBeforeAnOverlappingManagerAssignmentWinsAndTheAssignmentIs400() throws Exception {
        long overlapping = definition(branch, "Ca chồng", "15:00", "18:00", true);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdRow("accounts", swapStaff, release);

        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approveSwap(managerToken));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(() -> assignOnSwapDay(overlapping));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(approve.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> denied = assign.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(denied.getStatusCode().value()).isEqualTo(400);
        assertThat(message(denied)).as("thấy phân ca vừa đổi sang ca đích").endsWith("(BR-NS-02)");
        assertThat(scheduledCountOnSwapDay()).isEqualTo(1);
        assertSwapLocksReleased();
    }

    @Test
    void anOverlappingManagerAssignmentQueuedBeforeASwapApprovalWinsAndTheApprovalIs400LeavingNoTrace()
            throws Exception {
        long overlapping = definition(branch, "Ca chồng", "15:00", "18:00", true);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdRow("accounts", swapStaff, release);

        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(() -> assignOnSwapDay(overlapping));
        awaitLockWaits(1);
        Future<ResponseEntity<Map<String, Object>>> approve = pool.submit(() -> approveSwap(managerToken));
        awaitLockWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(assign.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> denied = approve.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(denied.getStatusCode().value()).isEqualTo(400);
        assertThat(message(denied)).startsWith("Ca đích chồng giờ với phân ca khác trong ngày: Ca chồng")
                .endsWith("(BR-NS-06)");
        assertThat(assignmentRow(swapAssignment)).containsEntry("shift_definition_id", morning);
        assertThat(fullRequestRow(swapRequest)).containsEntry("status", "PENDING");
        assertThat(swapAuditCount(swapAssignment)).isZero();
        assertSwapLocksReleased();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> approve(String token) {
        return call(HttpMethod.POST, approvePath(request), token);
    }

    private ResponseEntity<Map<String, Object>> approveSwap(String token) {
        return call(HttpMethod.POST, approvePath(swapRequest), token);
    }

    private ResponseEntity<Map<String, Object>> deactivateTarget() {
        return call(HttpMethod.PATCH, PATH + "/" + target, secondManagerToken, "{'active':false}");
    }

    private ResponseEntity<Map<String, Object>> assignOnSwapDay(long definitionId) {
        return call(HttpMethod.POST, ASSIGNMENTS, secondManagerToken,
                body(swapStaff, definitionId, DAY.plusDays(1).toString()));
    }

    private int scheduledCountOnSwapDay() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM shift_assignments WHERE staff_id = ? AND work_date = ? AND status = 'SCHEDULED'""",
                Integer.class, swapStaff, DAY.plusDays(1));
    }

    /** Sau mọi request của nhóm SWAP: tài khoản, ca đích, phân ca và yêu cầu đều lấy khóa được ngay. */
    private void assertSwapLocksReleased() {
        assertThat(canLockNowait("accounts", swapStaff)).isTrue();
        assertThat(canLockNowait("shift_definitions", target)).isTrue();
        assertThat(canLockNowait("shift_assignments", swapAssignment)).isTrue();
        assertThat(canLockNowait("shift_requests", swapRequest)).isTrue();
    }

    /** Một transaction khác giữ {@code FOR NO KEY UPDATE} trên một dòng (như PATCH ca giữ dòng ca) tới khi {@code release}. */
    private Future<?> holdRow(String table, long id, CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Trigger tạm: duyệt SWAP dừng ngay sau UPDATE phân ca sang ca đích (đang giữ accounts, FOR SHARE ca đích, phân ca). */
    private void pauseAfterSwap() {
        createPauseFunction();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sal_pause ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_sal_pause AFTER UPDATE ON shift_assignments FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.shift_definition_id = %d) EXECUTE FUNCTION sal_test_pause()"""
                .formatted(swapAssignment, target));
    }

    /** Trigger tạm: PATCH ngừng dùng ca đích dừng ngay sau UPDATE (đang giữ FOR NO KEY UPDATE dòng ca). */
    private void pauseDefinitionDeactivation() {
        createPauseFunction();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sal_pause ON shift_definitions");
        jdbc.execute("""
                CREATE TRIGGER trg_sal_pause AFTER UPDATE ON shift_definitions FOR EACH ROW
                WHEN (NEW.id = %d AND NOT NEW.is_active) EXECUTE FUNCTION sal_test_pause()""".formatted(target));
    }

    private void createPauseFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sal_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
    }

    private ResponseEntity<Map<String, Object>> reject() {
        return call(HttpMethod.POST, rejectPath(request), managerToken, rejectBody(NOTE));
    }

    private ResponseEntity<Map<String, Object>> withdraw() {
        return call(HttpMethod.POST, withdrawPath(request), staffToken);
    }

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(HttpMethod.POST, cancelPath(assignment), secondManagerToken, reasonBody("Quản lý hủy"));
    }

    /** Phân ca ca chiều 13:00 của hôm nay: chưa bắt đầu lúc 10:00 (duyệt được) và đã tới ngày (ghi vắng được). */
    private long todayAssignment() {
        long afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        return data.assignment(branch, staff, afternoon, TODAY, "SCHEDULED", "MANAGER", null);
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

    /**
     * Trigger tạm: sau khi phân ca của test sang {@code status}, request đang ghi chờ advisory lock {@link #PAUSE_KEY}.
     * Lúc đó request đó đã khóa {@code accounts} và đang giữ khóa dòng phân ca; test giữ advisory lock ở transaction khác.
     */
    private void pauseAfter(String status) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sal_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sal_pause ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_sal_pause AFTER UPDATE ON shift_assignments FOR EACH ROW
                WHEN (NEW.id = %d AND NEW.status = '%s') EXECUTE FUNCTION sal_test_pause()"""
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
