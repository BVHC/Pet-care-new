package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.mapper.ShiftRequestMapper;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã ghi</b> trong duyệt yêu cầu nghỉ và đổi ca (workforce-v1 #13; docs/adr/0139; ca {@code swap…} cho
 * {@code SWAP}, N3-S5b: phân ca phải quay về ca gốc và khóa ca đích phải nhả): duyệt ghi UPDATE phân ca rồi UPDATE
 * yêu cầu rồi hai dòng audit (phân ca, yêu cầu) rồi hai dòng outbox (IN_APP, EMAIL), tất cả cùng một transaction nên lỗi ở
 * bất kỳ bước nào rút hết, kể cả UPDATE phân ca đã chạy trước đó. Lỗi được gây bằng trigger tạm của Postgres (chạy ngay
 * nên nổi trong service, không đợi commit) và bằng spy mapper cho hai ảnh chụp audit "sau" và cho dựng response sau
 * cùng. Mỗi ca: dấu vết y như trước (yêu cầu {@code PENDING}, phân ca {@code SCHEDULED} và {@code xmin} không đổi, không
 * audit, không outbox, không còn khóa), rồi khi hết lỗi thì gọi lại thành công đúng một lần. Tách riêng vì
 * {@code @MockitoSpyBean} thay bean của cả context (khuôn {@code ShiftRequestRejectRollbackIT}). Era <b>năm 2082</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestApproveRollbackIT.TestBeans.class})
class ShiftRequestApproveRollbackIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2082-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2082, 3, 21);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private ShiftRequestMapper requestMapper;
    @MockitoSpyBean
    private ShiftAssignmentMapper assignmentMapper;

    private long branch;
    private long manager;
    private long staff;
    private long assignment;
    private long request;
    private long morning;
    private long afternoon;
    private long swapAssignment;
    private long swapRequest;
    private String managerToken;
    private String before;
    private String swapBefore;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        // SWAP (N3-S5b): ngày khác để không chồng giờ với phân ca của yêu cầu nghỉ.
        swapAssignment = data.assignment(branch, staff, morning, DAY.plusDays(1), "SCHEDULED", "SELF", null);
        swapRequest = data.swapRequest(swapAssignment, staff, afternoon, "PENDING", null);
        managerToken = token(manager);
        before = approveFootprint(request, assignment, staff);
        assertThat(before).contains("PENDING").contains("SCHEDULED").contains("audit=0/0").contains("outbox=0");
        swapBefore = approveFootprint(swapRequest, swapAssignment, staff);
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            reset(requestMapper, assignmentMapper);
        } finally {
            purgeApprovedOutbox(staff);
            finish();
        }
    }

    @Test
    void aFailureWhileWritingTheAssignmentLeavesEverythingUntouched() {
        failOnUpdate("shift_assignments", "OLD.id = " + assignment);

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileWritingTheRequestAfterTheAssignmentWasUpdatedRollsTheAssignmentBack() {
        failOnUpdate("shift_requests", "OLD.id = " + request);

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anAuditFailureOnTheAssignmentAuditRollsBackBothUpdates() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_ASSIGNMENT_ON_LEAVE' AND NEW.entity_id = " + assignment);

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anAuditFailureOnTheRequestAuditAlsoRollsBackTheAssignmentAuditInsertedBeforeIt() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_REQUEST_APPROVED' AND NEW.entity_id = " + request);

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertThat(onLeaveAuditCount(assignment)).as("audit phân ca đã chèn trước đó cũng phải mất").isZero();
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anOutboxFailureOnTheInAppNoticeRollsBackTheUpdatesAndBothAudits() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + APPROVED_APP + "' AND NEW.recipient_account_id = "
                + staff);

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anOutboxFailureOnTheEmailAlsoRollsBackTheInAppNoticeInsertedBeforeIt() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + APPROVED_MAIL + "' AND NEW.recipient_email = '"
                + accountEmail(staff) + "'");

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        assertThat(approvedOutbox(staff)).as("bản trong app đã chèn trước đó cũng phải mất").isEmpty();
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aResponseBuildingFailureAsTheVeryLastStepRollsBackEveryEffect() {
        doThrow(new IllegalStateException("mapper broke")).when(requestMapper)
                .toResponse(any(ShiftRequest.class), any(), any(), any(), any());

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        reset(requestMapper);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureOfTheRequestAfterSnapshotAfterBothWritesAndTheFirstAuditRollsEverythingBack() {
        // Lần gọi toSnapshot(ShiftRequest) thứ nhất là ảnh "trước" (trước khi ghi), thứ hai là ảnh "sau" của audit.
        doCallRealMethod().doThrow(new IllegalStateException("snapshot broke")).when(requestMapper)
                .toSnapshot(any(ShiftRequest.class));

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        reset(requestMapper);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureOfTheAssignmentAfterSnapshotAfterBothWritesRollsEverythingBack() {
        doCallRealMethod().doThrow(new IllegalStateException("snapshot broke")).when(assignmentMapper)
                .toSnapshot(any(ShiftAssignment.class));

        ResponseEntity<Map<String, Object>> failed = approve();

        assertFailedAndUntouched(failed);
        reset(assignmentMapper);
        assertRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- SWAP (N3-S5b): cùng tám điểm lỗi

    @Test
    void swapAFailureWhileWritingTheAssignmentLeavesEverythingUntouched() {
        failOnUpdate("shift_assignments", "OLD.id = " + swapAssignment);

        assertSwapFailedAndUntouched(approveSwap());
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAFailureWhileWritingTheRequestRollsTheShiftChangeBack() {
        failOnUpdate("shift_requests", "OLD.id = " + swapRequest);

        assertSwapFailedAndUntouched(approveSwap());
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAnAuditFailureOnTheSwappedAuditRollsBackBothUpdates() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_ASSIGNMENT_SWAPPED' AND NEW.entity_id = " + swapAssignment);

        assertSwapFailedAndUntouched(approveSwap());
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAnAuditFailureOnTheRequestAuditAlsoRollsBackTheSwappedAuditInsertedBeforeIt() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_REQUEST_APPROVED' AND NEW.entity_id = " + swapRequest);

        assertSwapFailedAndUntouched(approveSwap());
        assertThat(swapAuditCount(swapAssignment)).as("audit đổi ca đã chèn trước đó cũng phải mất").isZero();
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAnOutboxFailureOnTheInAppNoticeRollsBackTheUpdatesAndBothAudits() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + APPROVED_APP + "' AND NEW.recipient_account_id = "
                + staff);

        assertSwapFailedAndUntouched(approveSwap());
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAnOutboxFailureOnTheEmailAlsoRollsBackTheInAppNoticeInsertedBeforeIt() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + APPROVED_MAIL + "' AND NEW.recipient_email = '"
                + accountEmail(staff) + "'");

        assertSwapFailedAndUntouched(approveSwap());
        assertThat(approvedOutbox(staff)).as("bản trong app đã chèn trước đó cũng phải mất").isEmpty();
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAFailureOfTheAssignmentAfterSnapshotAfterBothWritesRollsEverythingBack() {
        doCallRealMethod().doThrow(new IllegalStateException("snapshot broke")).when(assignmentMapper)
                .toSnapshot(any(ShiftAssignment.class));

        assertSwapFailedAndUntouched(approveSwap());
        reset(assignmentMapper);
        assertSwapRetrySucceedsExactlyOnce();
    }

    @Test
    void swapAResponseBuildingFailureAsTheVeryLastStepRollsBackEveryEffect() {
        doThrow(new IllegalStateException("mapper broke")).when(requestMapper)
                .toResponse(any(ShiftRequest.class), any(), any(), any(), any());

        assertSwapFailedAndUntouched(approveSwap());
        reset(requestMapper);
        assertSwapRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> approve() {
        return call(HttpMethod.POST, approvePath(request), managerToken);
    }

    private ResponseEntity<Map<String, Object>> approveSwap() {
        return call(HttpMethod.POST, approvePath(swapRequest), managerToken);
    }

    /** Như {@link #assertFailedAndUntouched} cho SWAP: phân ca vẫn ở ca gốc, khóa ca đích {@code FOR SHARE} đã nhả. */
    private void assertSwapFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(approveFootprint(swapRequest, swapAssignment, staff)).isEqualTo(swapBefore);
        assertThat(fullRequestRow(swapRequest)).containsEntry("status", "PENDING").containsEntry("decided_by", null)
                .containsEntry("closed_at", null);
        assertThat(assignmentRow(swapAssignment)).containsEntry("shift_definition_id", morning)
                .containsEntry("status", "SCHEDULED");
        assertThat(approveRequestAuditCount(swapRequest)).isZero();
        assertThat(swapAuditCount(swapAssignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
        assertThat(canLockNowait("accounts", staff)).as("khóa tài khoản đã nhả").isTrue();
        assertThat(canLockNowait("shift_definitions", afternoon)).as("khóa ca đích đã nhả").isTrue();
        assertThat(canLockNowait("shift_assignments", swapAssignment)).isTrue();
        assertThat(canLockNowait("shift_requests", swapRequest)).isTrue();
        assertThat(approveFootprint(request, assignment, staff)).as("yêu cầu nghỉ bên cạnh không bị đụng")
                .isEqualTo(before);
    }

    private void assertSwapRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = approveSwap();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(swapRequest)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager)
                .containsEntry("current_shift_definition_id", morning);
        assertThat(assignmentRow(swapAssignment)).containsEntry("shift_definition_id", afternoon)
                .containsEntry("status", "SCHEDULED");
        assertThat(approveRequestAuditCount(swapRequest)).as("đúng một audit yêu cầu sau khi hết lỗi").isEqualTo(1);
        assertThat(swapAuditCount(swapAssignment)).as("đúng một audit đổi ca sau khi hết lỗi").isEqualTo(1);
        assertThat(approvedOutbox(staff)).as("đúng một cặp thông báo").hasSize(2);
    }

    /** Lỗi hạ tầng là 5xx (không phải lỗi nghiệp vụ) và để lại đúng ảnh chụp ban đầu, không còn khóa. */
    private void assertFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(approveFootprint(request, assignment, staff)).isEqualTo(before);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING").containsEntry("decided_by", null)
                .containsEntry("decision_note", null).containsEntry("closed_at", null);
        assertThat(assignmentRow(assignment)).containsEntry("status", "SCHEDULED")
                .containsEntry("status_changed_by", null).containsEntry("status_changed_at", null)
                .containsEntry("reason", null);
        assertThat(approveRequestAuditCount(request)).isZero();
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
        assertThat(canLockNowait("accounts", staff)).as("khóa tài khoản đã nhả").isTrue();
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();
    }

    private void assertRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = approve();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE")
                .containsEntry("status_changed_by", manager);
        assertThat(approveRequestAuditCount(request)).as("đúng một audit yêu cầu sau khi hết lỗi").isEqualTo(1);
        assertThat(onLeaveAuditCount(assignment)).as("đúng một audit phân ca sau khi hết lỗi").isEqualTo(1);
        assertThat(approvedOutbox(staff)).as("đúng một cặp thông báo").hasSize(2);
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

    private void failOnInsert(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sar_fail BEFORE INSERT ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sar_test_fail()"
                .formatted(table, condition));
    }

    private void failOnUpdate(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sar_fail BEFORE UPDATE ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sar_test_fail()"
                .formatted(table, condition));
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sar_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'sar test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
    }

    private void dropTriggers() {
        for (String table : new String[] {"notification_outbox", "shift_requests", "shift_assignments", "audit_logs"}) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sar_fail ON " + table);
        }
    }
}
