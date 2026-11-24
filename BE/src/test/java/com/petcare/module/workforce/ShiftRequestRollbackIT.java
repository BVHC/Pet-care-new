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
 * Lỗi <b>sau khi đã ghi</b> trong gửi và rút yêu cầu (workforce-v1 #9, #11; docs/adr/0149 bước 6): gửi ghi yêu cầu rồi
 * mỗi quản lý một dòng outbox, rút ghi một UPDATE, tất cả cùng một transaction nên lỗi ở bất kỳ bước nào rút hết. Lỗi
 * được gây bằng trigger tạm của Postgres (INSERT / UPDATE chạy ngay nên lỗi nổi trong service, không đợi commit) và bằng
 * spy mapper cho lỗi dựng response sau cùng. Mỗi ca: dấu vết y như trước, rồi khi hết lỗi thì gọi lại thành công đúng một
 * lần. Tách riêng vì {@code @MockitoSpyBean} thay bean của cả context (khuôn {@code ShiftAssignmentCancelRollbackIT}).
 * Era <b>năm 2068</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestRollbackIT.TestBeans.class})
class ShiftRequestRollbackIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2068-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2068, 3, 21);

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
    private long firstManager;
    private long secondManager;
    private long staff;
    private long assignment;
    private String token;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        firstManager = data.staff("BRANCH_MANAGER", branch);
        secondManager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        long morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        token = token(staff);
        before = requestFootprint(assignment, staff);
        assertThat(before).contains("requests=[]").contains("outbox=0").contains("audit=0");
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            reset(mapper);
        } finally {
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- #9 gửi: yêu cầu + outbox của từng quản lý

    @Test
    void anOutboxFailureOnTheLastManagersNoticeRollsBackTheRequestAndEveryNoticeInsertedBeforeIt() {
        failOnInsert("notification_outbox", "NEW.template_code = '" + TEMPLATE + "' AND NEW.recipient_account_id = "
                + Math.max(firstManager, secondManager));

        ResponseEntity<Map<String, Object>> failed = submit();

        assertSubmitFailedAndUntouched(failed);
        assertThat(submittedOutbox(firstManager)).isEmpty();
        assertThat(submittedOutbox(secondManager)).isEmpty();
        assertSubmitRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileInsertingTheRequestItselfLeavesNothingBehind() {
        failOnInsert("shift_requests", "NEW.assignment_id = " + assignment);

        ResponseEntity<Map<String, Object>> failed = submit();

        assertSubmitFailedAndUntouched(failed);
        assertSubmitRetrySucceedsExactlyOnce();
    }

    @Test
    void aResponseBuildingFailureAsTheVeryLastStepRollsBackTheRequestAndAllNotices() {
        doThrow(new IllegalStateException("mapper broke")).when(mapper)
                .toResponse(any(ShiftRequest.class), any(), any(), any(), any());

        ResponseEntity<Map<String, Object>> failed = submit();

        assertSubmitFailedAndUntouched(failed);
        reset(mapper);
        assertSubmitRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- #11 rút: một UPDATE

    @Test
    void aFailureWhileWritingTheWithdrawalLeavesTheRequestPending() {
        long request = pendingRequest(assignment, staff);
        String pendingState = requestFootprint(assignment, staff);
        failOnUpdate("shift_requests", "OLD.id = " + request);

        ResponseEntity<Map<String, Object>> failed = withdraw(request);

        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(requestFootprint(assignment, staff)).isEqualTo(pendingState);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING").containsEntry("closed_at", null);
        assertWithdrawRetrySucceeds(request);
    }

    @Test
    void aResponseBuildingFailureAfterTheWithdrawalWasWrittenLeavesTheRequestPending() {
        long request = pendingRequest(assignment, staff);
        String pendingState = requestFootprint(assignment, staff);
        doThrow(new IllegalStateException("mapper broke")).when(mapper)
                .toResponse(any(ShiftRequest.class), any(), any(), any(), any());

        ResponseEntity<Map<String, Object>> failed = withdraw(request);

        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(requestFootprint(assignment, staff)).isEqualTo(pendingState);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING").containsEntry("closed_at", null);
        reset(mapper);
        assertWithdrawRetrySucceeds(request);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> submit() {
        return call(HttpMethod.POST, REQUESTS, token, leaveBody(assignment));
    }

    private ResponseEntity<Map<String, Object>> withdraw(long requestId) {
        return call(HttpMethod.POST, withdrawPath(requestId), token);
    }

    /** Lỗi hạ tầng là 5xx (không phải lỗi nghiệp vụ) và để lại đúng ảnh chụp ban đầu: không yêu cầu, không outbox. */
    private void assertSubmitFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().is5xxServerError()).as("trạng thái %s", failed.getStatusCode()).isTrue();
        dropTriggers();
        assertThat(requestFootprint(assignment, staff)).isEqualTo(before);
        assertThat(requestCountOf(assignment)).isZero();
    }

    private void assertSubmitRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = submit();

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(requestCountOf(assignment)).as("đúng một yêu cầu sau khi hết lỗi").isEqualTo(1);
        assertThat(submittedOutbox(firstManager)).as("mỗi quản lý đúng một thông báo").hasSize(1);
        assertThat(submittedOutbox(secondManager)).hasSize(1);
    }

    private void assertWithdrawRetrySucceeds(long request) {
        ResponseEntity<Map<String, Object>> retry = withdraw(request);

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "WITHDRAWN");
        assertThat(requestCountOf(assignment)).isEqualTo(1);
    }

    private void failOnInsert(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sr_fail BEFORE INSERT ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sr_test_fail()"
                .formatted(table, condition));
    }

    private void failOnUpdate(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sr_fail BEFORE UPDATE ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sr_test_fail()"
                .formatted(table, condition));
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sr_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'sr test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
    }

    private void dropTriggers() {
        for (String table : new String[] {"notification_outbox", "shift_requests"}) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sr_fail ON " + table);
        }
    }
}
