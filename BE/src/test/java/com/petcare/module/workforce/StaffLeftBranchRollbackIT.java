package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.support.MutableClock;

/**
 * Lỗi <b>ở từng điểm</b> của chuỗi "nhân viên rời chi nhánh" (BR-NS-09; docs/adr/0138 bước 5): thao tác gốc của identity
 * (nhân viên, chi nhánh, phiên, audit) và mọi hiệu ứng của workforce (phân ca, yêu cầu, outbox) cùng một transaction nên
 * một lỗi ở bất kỳ bước nào rút <b>tất cả</b>, cho cả vô hiệu hóa lẫn điều chuyển. Lỗi gây bằng trigger tạm của Postgres
 * (UPDATE / INSERT chạy ngay nên lỗi nổi trong transaction, không đợi commit) và bằng spy mapper cho lỗi dựng response sau
 * cùng, <b>sau khi listener đã xong</b>. Lỗi hạ tầng là 500 {@code INTERNAL_ERROR} và không bị ghi nhầm thành "bị từ chối
 * quyền". Mỗi ca: ảnh chụp y như trước, rồi khi hết lỗi gọi lại thành công đúng một lần. Tách riêng khỏi
 * {@code StaffLeftBranchIT} vì {@code @MockitoSpyBean} thay bean của cả context (khuôn {@code ShiftAssignmentCancelRollbackIT}).
 * Era <b>năm 2059</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StaffLeftBranchRollbackIT.TestBeans.class})
class StaffLeftBranchRollbackIT extends StaffLeftBranchItSupport {

    private static final Instant NOW = Instant.parse("2059-03-14T03:00:00Z"); // 10:00 giờ Việt Nam
    private static final String[] TRIGGER_TABLES = {"shift_assignments", "shift_requests", "notification_outbox"};

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private StaffAdminMapper mapper;

    private String token;
    private String before;

    @BeforeEach
    void setUp() {
        seedWorld(NOW);
        token = token(superManager);
        token(staff); // một phiên đang mở của nhân viên: vô hiệu hóa hủy nó, nên rollback phải giữ nó
        before = worldOf(staff);
        assertThat(before).contains("sessions=1").contains("outbox=0");
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            reset(mapper);
        } finally {
            tearDownWorld();
        }
    }

    @ParameterizedTest(name = "transfer={0}")
    @ValueSource(booleans = {false, true})
    void aFailureWhileCancellingTheAssignmentsRollsBackTheStaffOperationAndEverythingElse(boolean transfer) {
        failOnUpdate("shift_assignments", "NEW.status = 'CANCELLED' AND NEW.staff_id = " + staff);

        assertFailedAndUntouched(act(transfer));
        assertRetrySucceedsExactlyOnce(transfer);
    }

    @ParameterizedTest(name = "transfer={0}")
    @ValueSource(booleans = {false, true})
    void aFailureWhileRejectingThePendingRequestRollsBackTheAlreadyUpdatedAssignments(boolean transfer) {
        failOnUpdate("shift_requests", "OLD.id = " + requestOnFuture);

        assertFailedAndUntouched(act(transfer));
        assertRetrySucceedsExactlyOnce(transfer);
    }

    @ParameterizedTest(name = "transfer={0}")
    @ValueSource(booleans = {false, true})
    void anOutboxFailureOnTheFirstEmailAlsoRollsBackTheInAppNoticeInsertedJustBefore(boolean transfer) {
        // Postgres không cho subquery trong WHEN của trigger: tra email trước rồi nhúng giá trị (UUID, an toàn khi nháy).
        failOnInsert("notification_outbox", "NEW.channel = 'EMAIL' AND NEW.template_code = 'SHIFT_ASSIGNMENT_CANCELLED'"
                + " AND NEW.recipient_email = '" + emailOf(staff) + "'");

        assertFailedAndUntouched(act(transfer));
        assertThat(cancelOutbox(staff)).as("dòng IN_APP đã chèn trước đó cũng phải mất").isEmpty();
        assertRetrySucceedsExactlyOnce(transfer);
    }

    @ParameterizedTest(name = "transfer={0}")
    @ValueSource(booleans = {false, true})
    void aResponseBuildingFailureAfterTheListenerFinishedRollsBackEverything(boolean transfer) {
        doThrow(new IllegalStateException("mapper broke")).when(mapper).toResponse(any(StaffDetailRow.class),
                anyBoolean());

        assertFailedAndUntouched(act(transfer));
        reset(mapper);
        assertRetrySucceedsExactlyOnce(transfer);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> act(boolean transfer) {
        return transfer ? transfer(token, staff, branchB) : disable(token, staff);
    }

    /** Lỗi hạ tầng là 500 {@code INTERNAL_ERROR}, không phải lỗi nghiệp vụ, và để lại đúng ảnh chụp ban đầu. */
    private void assertFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().value()).as("trạng thái %s", failed.getStatusCode()).isEqualTo(500);
        assertThat(errorCode(failed)).isEqualTo("INTERNAL_ERROR");
        dropTriggers();
        assertThat(worldOf(staff)).as("nhân viên, phiên, audit, phân ca, yêu cầu, outbox phải y như trước").isEqualTo(before);
        assertThat(denialAuditCount(superManager)).as("lỗi listener không được ghi thành 'bị từ chối quyền'").isZero();
    }

    private void assertRetrySucceedsExactlyOnce(boolean transfer) {
        ResponseEntity<Map<String, Object>> retry = act(transfer);

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        for (long id : cancelledByLeaving()) {
            assertThat(assignmentRow(id)).containsEntry("status", "CANCELLED");
        }
        assertThat(requestRow(requestOnFuture)).containsEntry("status", "REJECTED");
        assertThat(cancelOutbox(staff)).as("đúng ba cặp thông báo sau khi hết lỗi").hasSize(6);
        assertThat(staffAuditCount(staff, transfer ? "STAFF_TRANSFERRED" : "STAFF_DISABLED"))
                .as("đúng một audit của thao tác gốc").isEqualTo(1);
        assertThat(activeSessions(staff)).isEqualTo(transfer ? 1 : 0);
    }

    private void failOnInsert(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sl_fail BEFORE INSERT ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sl_test_fail()"
                .formatted(table, condition));
    }

    private void failOnUpdate(String table, String condition) {
        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_sl_fail BEFORE UPDATE ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION sl_test_fail()"
                .formatted(table, condition));
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sl_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'sl test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
    }

    private void dropTriggers() {
        for (String table : TRIGGER_TABLES) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sl_fail ON " + table);
        }
    }
}
