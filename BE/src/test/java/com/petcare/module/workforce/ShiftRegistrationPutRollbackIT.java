package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Lỗi <b>sau khi đã ghi</b> trong đăng ký ca theo tuần (workforce-v1 #15; docs/adr/0149 mục "Đăng ký tuần — ghi #15"):
 * {@code PUT} hủy phân ca bị bỏ (UPDATE), đóng yêu cầu chờ của chúng (UPDATE hàng loạt), chèn phân ca mới (INSERT) rồi
 * đọc lại và dựng response, tất cả cùng một transaction nên lỗi ở bất kỳ bước nào rút hết, kể cả các lệnh đã chạy trước
 * đó. Bốn điểm lỗi: (i) UPDATE phân ca (lần ghi đầu), (ii) UPDATE yêu cầu (sau khi phân ca đã bị hủy), (iii) INSERT phân
 * ca mới (sau cả hai), (iv) dựng response (sau mọi lần ghi). Ba điểm đầu được gây bằng trigger tạm của Postgres (chạy
 * ngay nên nổi trong service, không đợi commit), điểm cuối bằng spy mapper. Mỗi ca: dấu vết y như trước (phân ca, yêu
 * cầu, {@code xmin}, audit, outbox), không còn khóa, kết nối trả về pool, rồi khi hết lỗi gọi lại thành công đúng một
 * lần và lần nữa không đổi gì. Tách riêng vì {@code @MockitoSpyBean} thay bean của cả context. Era <b>năm 2089</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationPutRollbackIT.TestBeans.class})
class ShiftRegistrationPutRollbackIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2089-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2089, 3, 15));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @MockitoSpyBean
    private ShiftAssignmentMapper assignmentMapper;
    @Autowired
    private DataSource dataSource;

    private long branch;
    private long staff;
    private long morning;
    private long afternoon;
    private List<Long> seeded;
    private long droppedRequest;
    private String token;
    private String before;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        seeded = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            seeded.add(data.assignment(branch, staff, morning, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
            seeded.add(data.assignment(branch, staff, afternoon, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
        }
        droppedRequest = pendingRequest(seeded.get(9), staff); // Thứ Sáu, ca chiều: sẽ bị bỏ
        token = token(staff);
        before = trace(staff);
        assertThat(before).contains("SCHEDULED").contains("PENDING");
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            reset(assignmentMapper);
        } finally {
            finish();
        }
    }

    @Test
    void aFailureOnTheFirstWriteTheCancelOfADroppedRowLeavesEverythingUntouched() {
        failOn("UPDATE", "shift_assignments", "OLD.id = " + seeded.get(9));

        ResponseEntity<Map<String, Object>> failed = put();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileClosingThePendingRequestRollsTheAlreadyCancelledRowBack() {
        failOn("UPDATE", "shift_requests", "OLD.id = " + droppedRequest);

        ResponseEntity<Map<String, Object>> failed = put();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileInsertingTheNewRowRollsBackTheCancelAndTheRequestClosing() {
        failOn("INSERT", "shift_assignments", "NEW.staff_id = " + staff);

        ResponseEntity<Map<String, Object>> failed = put();

        assertFailedAndUntouched(failed);
        assertRetrySucceedsExactlyOnce();
    }

    @Test
    void aFailureWhileBuildingTheResponseAfterEveryWriteRollsEverythingBack() {
        doThrow(new IllegalStateException("mapper broke")).when(assignmentMapper)
                .toResponse(any(ShiftAssignment.class), any(), any(), any());

        ResponseEntity<Map<String, Object>> failed = put();

        assertFailedAndUntouched(failed);
        reset(assignmentMapper);
        assertRetrySucceedsExactlyOnce();
    }

    // ---------------------------------------------------------------- kiểm tra chung

    private ResponseEntity<Map<String, Object>> put() {
        List<String> items = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            items.add(item(WEEK.plusDays(day), morning));
            if (day < 4) {
                items.add(item(WEEK.plusDays(day), afternoon));
            }
        }
        items.add(item(WEEK.plusDays(5), morning)); // thay (Thứ Sáu, ca chiều) bằng (Thứ Bảy, ca sáng)
        return put(token, WEEK, itemsBody(items.toArray(String[]::new)));
    }

    private void assertFailedAndUntouched(ResponseEntity<Map<String, Object>> failed) {
        assertThat(failed.getStatusCode().value()).as("trạng thái %s", failed.getStatusCode()).isEqualTo(500);
        assertThat(errorCode(failed)).isEqualTo("INTERNAL_ERROR");
        dropTriggers();
        assertThat(trace(staff)).as("dấu vết (xmin, trạng thái, audit, outbox) y như trước").isEqualTo(before);
        assertThat(assignmentRow(seeded.get(9))).containsEntry("status", "SCHEDULED").containsEntry("reason", null)
                .containsEntry("status_changed_by", null).containsEntry("status_changed_at", null);
        assertThat(requestRow(droppedRequest)).containsEntry("status", "PENDING").containsEntry("decision_note", null)
                .containsEntry("closed_at", null).containsEntry("decided_by", null);
        assertThat(scheduledRowsOf(staff)).hasSize(10);
        assertThat(rowsOf(staff)).as("không có dòng mới nào").hasSize(10);
        assertThat(canLockNowait("accounts", staff)).as("khóa tài khoản đã nhả").isTrue();
        for (long id : seeded) {
            assertThat(canLockNowait("shift_assignments", id)).as("phân ca %d", id).isTrue();
        }
        assertThat(canLockNowait("shift_requests", droppedRequest)).isTrue();
        assertThat(activeConnections()).as("kết nối đã trả về pool").isZero();
    }

    private void assertRetrySucceedsExactlyOnce() {
        ResponseEntity<Map<String, Object>> retry = put();

        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(seeded.get(9))).containsEntry("status", "CANCELLED")
                .containsEntry("reason", SELF_EDIT_REASON).containsEntry("status_changed_by", staff);
        assertThat(requestRow(droppedRequest)).containsEntry("status", "REJECTED")
                .containsEntry("decision_note", EDITED_NOTE).containsEntry("decided_by", null);
        List<Map<String, Object>> scheduled = scheduledRowsOf(staff);
        assertThat(scheduled).hasSize(10);
        assertThat(rowsOf(staff)).as("đúng một dòng mới sau khi hết lỗi").hasSize(11);
        assertThat(scheduled.get(9)).containsEntry("work_date", WEEK.plusDays(5).toString())
                .containsEntry("source", "SELF").containsEntry("shift_definition_id", morning);

        String afterFirst = trace(staff);
        assertThat(put().getStatusCode().value()).isEqualTo(200);
        assertThat(trace(staff)).as("lần gửi lại cùng nội dung không đổi gì").isEqualTo(afterFirst);
    }

    // ---------------------------------------------------------------- hạ tầng gây lỗi

    private void failOn(String event, String table, String condition) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION srr_test_fail() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'srr test: lỗi cố ý';
                END $$ LANGUAGE plpgsql""");
        dropTriggers();
        jdbc.execute("CREATE TRIGGER trg_srr_fail BEFORE %s ON %s FOR EACH ROW WHEN (%s) EXECUTE FUNCTION srr_test_fail()"
                .formatted(event, table, condition));
    }

    private void dropTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srr_fail ON shift_assignments");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srr_fail ON shift_requests");
    }

    private int activeConnections() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
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
}
