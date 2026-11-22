package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.mapper.ShiftRequestMapper;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã ghi</b> trong từ chối yêu cầu (workforce-v1 #14; docs/adr/0139): từ chối ghi một UPDATE yêu cầu rồi
 * một dòng audit rồi hai dòng outbox (IN_APP, EMAIL), tất cả cùng một transaction nên lỗi ở bất kỳ bước nào rút hết.
 * Lỗi được gây bằng trigger tạm của Postgres (INSERT / UPDATE chạy ngay nên lỗi nổi trong service, không đợi commit) và
 * bằng spy mapper cho lỗi dựng response sau cùng. Mỗi ca: dấu vết y như trước (yêu cầu còn {@code PENDING}, phân ca và
 * {@code xmin} không đổi, không audit, không outbox, không còn khóa), rồi khi hết lỗi thì gọi lại thành công đúng một
 * lần. Tách riêng vì {@code @MockitoSpyBean} thay bean của cả context (khuôn {@code ShiftRequestRollbackIT}). Era
 * <b>năm 2079</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestRejectRollbackIT.TestBeans.class})
class ShiftRequestRejectRollbackIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2079-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2079, 3, 21);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private ShiftRequestMapper mapper;

    private long branch;
    private long manager;
    private long staff;
    private long assignment;
    private long request;
    private String managerToken;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        managerToken = token(manager);
        before = rejectFootprint(request, assignment, staff);
        assertThat(before).contains("PENDING").contains("audit=0").contains("outbox=0");
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            reset(mapper);
        } finally {
            purgeRejectedOutbox(staff);
            finish();
        }
    }

    @Test
    void aFailureWhileWritingTheRejectionLeavesTheRequestPendingAndNothingElse() {
        failOnUpdate("shift_requests", "OLD.id = " + request);

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anAuditFailureAfterTheRequestWasUpdatedRollsTheUpdateBack() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_REQUEST_REJECTED' AND NEW.entity_id = " + request);

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anOutboxFailureOnTheInAppNoticeRollsBackTheRequestAndTheAudit() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + REJECTED_APP + "' AND NEW.recipient_account_id = "
                + staff);

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anOutboxFailureOnTheSecondNoticeTheEmailAlsoRollsBackTheInAppNoticeInsertedBeforeIt() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + REJECTED_MAIL + "' AND NEW.recipient_email = '"
                + accountEmail(staff) + "'");

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        assertThat(rejectedOutbox(staff)).as("bản trong app đã chèn trước đó cũng phải mất").isEmpty();
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aResponseBuildingFailureAsTheVeryLastStepRollsBackTheRequestTheAuditAndBothNotices() {
        doThrow(new IllegalStateException("mapper broke")).when(mapper)
                .toResponse(any(ShiftRequest.class), any(), any(), any(), any());

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        reset(mapper);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureOfTheAuditSnapshotAfterTheWriteAlsoRollsEverythingBack() {
        doThrow(new IllegalStateException("snapshot broke")).when(mapper).toSnapshot(any(ShiftRequest.class));

        ResponseEntity<Map<String, Object>> failed = reject();

        assertFailedAndUntouched(failed);
        reset(mapper);
        assertRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> reject() {
        return call(HttpMethod.POST, rejectPath(request), managerToken, rejectBody(NOTE));
    }

    /** Lỗi hạ tầng là 5xx (không phải lỗi nghiệp vụ) và để lại đúng ảnh chụp ban đầu, không còn khóa. */
    private void assertFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(rejectFootprint(request, assignment, staff)).isEqualTo(before);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING").containsEntry("decided_by", null)
                .containsEntry("decision_note", null).containsEntry("closed_at", null);
        assertThat(rejectAuditCount(request)).isZero();
        assertThat(rejectedOutbox(staff)).isEmpty();
        assertThat(canLockNowait("accounts", staff)).as("khóa tài khoản đã nhả").isTrue();
        assertThat(canLockNowait("shift_assignments", assignment)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();
    }

    private void assertRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = reject();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
        assertThat(rejectAuditCount(request)).as("đúng một audit sau khi hết lỗi").isEqualTo(1);
        assertThat(rejectedOutbox(staff)).as("đúng một cặp thông báo").hasSize(2);
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
        jdbc.execute("CREATE TRIGGER trg_srr_fail BEFORE INSERT ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION srr_test_fail()"
                .formatted(table, condition));
    }

    private void failOnUpdate(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_srr_fail BEFORE UPDATE ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION srr_test_fail()"
                .formatted(table, condition));
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION srr_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'srr test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
    }

    private void dropTriggers() {
        for (String table : new String[] {"notification_outbox", "shift_requests", "audit_logs"}) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_srr_fail ON " + table);
        }
    }
}
