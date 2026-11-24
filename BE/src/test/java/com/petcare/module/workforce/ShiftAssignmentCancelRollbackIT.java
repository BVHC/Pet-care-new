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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>sau khi đã ghi</b> trong hủy phân ca (workforce-v1 #6; docs/adr/0149 bước 4): bốn hiệu ứng (phân ca, Yêu cầu#5,
 * audit, outbox) cùng một transaction nên một lỗi ở bất kỳ bước nào rút cả bốn. Lỗi được gây bằng trigger tạm của
 * Postgres (INSERT {@code IDENTITY} chạy ngay nên lỗi nổi trong service, không đợi commit) và bằng spy mapper cho lỗi
 * dựng response sau cùng. Mỗi ca: dấu vết y như trước, rồi khi hết lỗi thì gọi lại thành công đúng một lần (một audit,
 * hai outbox, yêu cầu đóng). Tách riêng khỏi {@code ShiftAssignmentCancelIT} vì {@code @MockitoSpyBean} thay bean của
 * cả context (khuôn {@code ShiftAssignmentRollbackIT}). Era <b>năm 2056</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentCancelRollbackIT.TestBeans.class})
class ShiftAssignmentCancelRollbackIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2056-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2056, 3, 21);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private ShiftAssignmentMapper mapper;

    private long branch;
    private long staff;
    private long assignment;
    private long request;
    private String token;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        long manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        token = token(manager);
        before = footprint(assignment, staff);
        assertThat(before).contains("audit=0").contains("outbox=0");
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            purgeCancelOutbox(staff);
        } finally {
            finish();
        }
    }

    @Test
    void anAuditInsertFailureAfterTheAssignmentAndTheRequestWereWrittenRollsBackBoth() {
        failOnInsert("audit_logs", "NEW.action = 'SHIFT_ASSIGNMENT_CANCELLED' AND NEW.entity_id = " + assignment);

        ResponseEntity<Map<String, Object>> failed = cancel();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void anOutboxFailureOnTheSecondNoticeAlsoRollsBackTheFirstNoticeTheAuditAndTheRows() {
        // Postgres không cho subquery trong WHEN của trigger: tra email trước rồi nhúng giá trị (UUID, an toàn khi nháy).
        String email = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, staff);
        failOnInsert("notification_outbox", "NEW.channel = 'EMAIL' AND NEW.template_code = 'SHIFT_ASSIGNMENT_CANCELLED'"
                + " AND NEW.recipient_email = '" + email + "'");

        ResponseEntity<Map<String, Object>> failed = cancel();

        assertFailedAndUntouched(failed);
        assertThat(cancelOutbox(staff)).as("dòng IN_APP đã chèn trước đó cũng phải mất").isEmpty();
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileClosingThePendingRequestRollsBackTheAlreadyFlushedAssignment() {
        failOnUpdate("shift_requests", "OLD.id = " + request);

        ResponseEntity<Map<String, Object>> failed = cancel();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aResponseBuildingFailureAsTheVeryLastStepRollsBackEverythingIncludingTheAuditAndBothNotices() {
        doThrow(new IllegalStateException("mapper broke")).when(mapper)
                .toResponse(any(ShiftAssignment.class), any(), any(ShiftDefinition.class), any());

        ResponseEntity<Map<String, Object>> failed = cancel();

        assertFailedAndUntouched(failed);
        reset(mapper);
        assertRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> cancel() {
        return call(org.springframework.http.HttpMethod.POST, cancelPath(assignment), token, reasonBody("Nghỉ lễ"));
    }

    /** Lỗi hạ tầng là 5xx (không phải lỗi nghiệp vụ) và để lại đúng ảnh chụp ban đầu. */
    private void assertFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(footprint(assignment, staff)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING").containsEntry("decision_note", null);
    }

    private void assertRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = cancel();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertThat(requestRow(request)).containsEntry("status", "REJECTED");
        assertThat(cancelAuditCount(assignment)).as("đúng một audit sau khi hết lỗi").isEqualTo(1);
        assertThat(cancelOutbox(staff)).as("đúng hai thông báo sau khi hết lỗi").hasSize(2);
    }

    private void failOnInsert(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_cx_fail BEFORE INSERT ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION cx_test_fail()"
                .formatted(table, condition));
    }

    private void failOnUpdate(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_cx_fail BEFORE UPDATE ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION cx_test_fail()"
                .formatted(table, condition));
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION cx_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'cx test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
    }

    private void dropTriggers() {
        for (String table : new String[] {"audit_logs", "notification_outbox", "shift_requests"}) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_cx_fail ON " + table);
        }
    }
}
