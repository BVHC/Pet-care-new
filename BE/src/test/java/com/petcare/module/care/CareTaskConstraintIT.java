package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskResult;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.repository.CareTaskRepository;

/**
 * Ràng buộc của {@code care_tasks} còn hiệu lực với entity mới (T39 phần 0, docs/adr/0079): NOT NULL, "đúng 1 đối tượng
 * theo loại", {@code DONE ⇔ result}, unique hai loại quá hạn (không phân biệt trạng thái), độ dài lý do, và mọi giá trị
 * enum Java đều được CHECK chấp nhận (không lệch tập giá trị). Mỗi vi phạm một transaction rồi rollback. Chèn với
 * {@code session_replication_role = replica}: bỏ kiểm FK (id cha giả), CHECK và unique vẫn áp dụng.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareTaskConstraintIT {

    private static final AtomicLong SOURCE = new AtomicLong(7_000_000L);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CareTaskRepository repository;

    private static final String INSERT = """
            INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, vaccination_id, medical_record_id,
                                    boarding_booking_id, due_date, status, result, cancel_reason, note, handled_by,
                                    handled_at)
            VALUES (%s)""";

    /**
     * Mỗi mệnh đề VALUES: branch, customer, pet, type, vaccination, record, booking, due, status, result, reason. Ba cột
     * xử lý (V22, docs/adr/0081) tự điền đủ theo {@code status} để các ca không nhắm vào chúng vẫn hợp lệ: {@code DONE}
     * có ghi chú + người + giờ, {@code CANCELLED} có giờ (lý do do bên gọi truyền), {@code OPEN} không có gì.
     */
    private static String values(String branch, String customer, String pet, String type, String vaccination,
            String record, String booking, String due, String status, String result, String reason) {
        String note = "'DONE'".equals(status) ? "'đã gọi'" : "NULL";
        String by = "'DONE'".equals(status) ? "1" : "NULL";
        String at = "'OPEN'".equals(status) ? "NULL" : "now()";
        return INSERT.formatted(String.join(", ", branch, customer, pet, type, vaccination, record, booking, due, status,
                result, reason, note, by, at));
    }

    /** Ca nhắm vào ba CHECK trạng thái ↔ cột xử lý: tự truyền ghi chú, lý do, người, giờ. */
    private static String handling(String status, String result, String note, String reason, String by, String at) {
        return INSERT.formatted(String.join(", ", "1", "2", "3", "'VACCINE_DUE'", "10", "NULL", "NULL", "current_date",
                "'" + status + "'", result, reason, note, by, at));
    }

    private static String valid(String type, String vaccination, String record, String booking, String status,
            String result) {
        String reason = "CANCELLED".equals(status) ? "'hủy tay'" : "NULL";
        return values("1", "2", "3", "'" + type + "'", vaccination, record, booking, "current_date", "'" + status + "'",
                result, reason);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** Chạy {@code sql} trong transaction bỏ kiểm FK; mong đợi lỗi ràng buộc; luôn rollback. */
    private void assertRejected(String why, String... statements) {
        tx().executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            assertThatThrownBy(() -> {
                for (String sql : statements) {
                    jdbc.update(sql);
                }
            }).as(why).isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });
    }

    private void assertAccepted(String why, String... statements) {
        tx().executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            assertThatCode(() -> {
                for (String sql : statements) {
                    jdbc.update(sql);
                }
            }).as(why).doesNotThrowAnyException();
            status.setRollbackOnly();
        });
    }

    // ================================================================ NOT NULL

    @Test
    void eachRequiredColumnRejectsNull() {
        for (String column : List.of("branch_id", "customer_id", "pet_id", "task_type", "due_date", "status")) {
            String[] cells = {"1", "2", "3", "'VACCINE_DUE'", "10", "NULL", "NULL", "current_date", "'OPEN'", "NULL",
                    "NULL"};
            List<String> names = List.of("branch_id", "customer_id", "pet_id", "task_type", "vaccination_id",
                    "medical_record_id", "boarding_booking_id", "due_date", "status", "result", "cancel_reason");
            cells[names.indexOf(column)] = "NULL";
            assertRejected(column + " NULL", values(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6],
                    cells[7], cells[8], cells[9], cells[10]));
        }
    }

    // ================================================================ đúng 1 đối tượng theo loại

    @Test
    void exactlyOneSourceColumnMatchingTheTypeIsAccepted() {
        assertAccepted("VACCINE_DUE", valid("VACCINE_DUE", "10", "NULL", "NULL", "OPEN", "NULL"));
        assertAccepted("VACCINE_OVERDUE", valid("VACCINE_OVERDUE", "10", "NULL", "NULL", "OPEN", "NULL"));
        assertAccepted("FOLLOW_UP_DUE", valid("FOLLOW_UP_DUE", "NULL", "20", "NULL", "OPEN", "NULL"));
        assertAccepted("PICKUP_OVERDUE", valid("PICKUP_OVERDUE", "NULL", "NULL", "30", "OPEN", "NULL"));
    }

    @Test
    void aWrongSourceShapeIsRejected() {
        assertRejected("thiếu nguồn", valid("VACCINE_DUE", "NULL", "NULL", "NULL", "OPEN", "NULL"));
        assertRejected("thừa nguồn thứ hai", valid("VACCINE_DUE", "10", "20", "NULL", "OPEN", "NULL"));
        assertRejected("nguồn sai cột (tái khám bằng mũi tiêm)", valid("FOLLOW_UP_DUE", "10", "NULL", "NULL", "OPEN", "NULL"));
        assertRejected("nguồn sai cột (quá hạn đón bằng bệnh án)", valid("PICKUP_OVERDUE", "NULL", "20", "NULL", "OPEN", "NULL"));
        assertRejected("ba nguồn", valid("PICKUP_OVERDUE", "10", "20", "30", "OPEN", "NULL"));
    }

    // ================================================================ DONE ⇔ result

    @Test
    void doneNeedsAResultAndOnlyDoneHasOne() {
        assertAccepted("DONE kèm kết quả", valid("VACCINE_DUE", "10", "NULL", "NULL", "DONE", "'REACHED'"));
        assertRejected("DONE thiếu kết quả", valid("VACCINE_DUE", "10", "NULL", "NULL", "DONE", "NULL"));
        assertRejected("OPEN có kết quả", valid("VACCINE_DUE", "10", "NULL", "NULL", "OPEN", "'REACHED'"));
        assertRejected("CANCELLED có kết quả", valid("VACCINE_DUE", "10", "NULL", "NULL", "CANCELLED", "'UNREACHABLE'"));
    }

    // ================================================================ unique quá hạn

    @Test
    void aSecondOverdueTaskOfTheSameSourceIsRejectedWhateverTheFirstOneStatus() {
        for (String status : List.of("OPEN", "CANCELLED")) {
            assertRejected("VACCINE_OVERDUE thứ hai (cái đầu " + status + ")",
                    valid("VACCINE_OVERDUE", "10", "NULL", "NULL", status, "NULL"),
                    valid("VACCINE_OVERDUE", "10", "NULL", "NULL", "OPEN", "NULL"));
            assertRejected("PICKUP_OVERDUE thứ hai (cái đầu " + status + ")",
                    valid("PICKUP_OVERDUE", "NULL", "NULL", "30", status, "NULL"),
                    valid("PICKUP_OVERDUE", "NULL", "NULL", "30", "OPEN", "NULL"));
        }
        assertRejected("DONE trước, OPEN sau",
                valid("VACCINE_OVERDUE", "10", "NULL", "NULL", "DONE", "'REACHED'"),
                valid("VACCINE_OVERDUE", "10", "NULL", "NULL", "OPEN", "NULL"));
    }

    @Test
    void theDueTypesAreNotUniqueInTheDatabase() {
        assertAccepted("VACCINE_DUE hai lần cùng mũi", valid("VACCINE_DUE", "10", "NULL", "NULL", "OPEN", "NULL"),
                valid("VACCINE_DUE", "10", "NULL", "NULL", "OPEN", "NULL"));
        assertAccepted("FOLLOW_UP_DUE hai lần cùng bệnh án", valid("FOLLOW_UP_DUE", "NULL", "20", "NULL", "OPEN", "NULL"),
                valid("FOLLOW_UP_DUE", "NULL", "20", "NULL", "OPEN", "NULL"));
        assertAccepted("VACCINE_DUE và VACCINE_OVERDUE cùng mũi", valid("VACCINE_DUE", "10", "NULL", "NULL", "OPEN", "NULL"),
                valid("VACCINE_OVERDUE", "10", "NULL", "NULL", "OPEN", "NULL"));
    }

    // ================================================================ trạng thái ↔ cột xử lý (V22, docs/adr/0081)

    @Test
    void openTaskCarriesNoHandlingColumns() {
        assertAccepted("OPEN trống", handling("OPEN", "NULL", "NULL", "NULL", "NULL", "NULL"));
        assertRejected("OPEN có ghi chú", handling("OPEN", "NULL", "'x'", "NULL", "NULL", "NULL"));
        assertRejected("OPEN có lý do hủy", handling("OPEN", "NULL", "NULL", "'x'", "NULL", "NULL"));
        assertRejected("OPEN có người xử lý", handling("OPEN", "NULL", "NULL", "NULL", "1", "NULL"));
        assertRejected("OPEN có giờ xử lý", handling("OPEN", "NULL", "NULL", "NULL", "NULL", "now()"));
    }

    @Test
    void doneTaskNeedsNoteHandlerAndTimeAndNoCancelReason() {
        assertAccepted("DONE đủ", handling("DONE", "'REACHED'", "'đã gọi'", "NULL", "1", "now()"));
        assertRejected("DONE thiếu ghi chú", handling("DONE", "'REACHED'", "NULL", "NULL", "1", "now()"));
        assertRejected("DONE thiếu người xử lý", handling("DONE", "'REACHED'", "'đã gọi'", "NULL", "NULL", "now()"));
        assertRejected("DONE thiếu giờ xử lý", handling("DONE", "'REACHED'", "'đã gọi'", "NULL", "1", "NULL"));
        assertRejected("DONE có lý do hủy", handling("DONE", "'REACHED'", "'đã gọi'", "'x'", "1", "now()"));
    }

    @Test
    void cancelledTaskNeedsReasonAndTimeButHandlerMayBeTheSystem() {
        assertAccepted("hệ thống hủy (handled_by NULL)", handling("CANCELLED", "NULL", "NULL", "'Thú đã mất'", "NULL", "now()"));
        assertAccepted("lễ tân hủy", handling("CANCELLED", "NULL", "NULL", "'Khách từ chối'", "1", "now()"));
        assertRejected("CANCELLED thiếu lý do", handling("CANCELLED", "NULL", "NULL", "NULL", "1", "now()"));
        assertRejected("CANCELLED thiếu giờ", handling("CANCELLED", "NULL", "NULL", "'x'", "1", "NULL"));
    }

    // ================================================================ giá trị, độ dài

    @Test
    void valuesOutsideTheCheckListsAndATooLongReasonAreRejected() {
        assertRejected("task_type lạ", valid("SOMETHING_ELSE", "10", "NULL", "NULL", "OPEN", "NULL"));
        assertRejected("status lạ", valid("VACCINE_DUE", "10", "NULL", "NULL", "REOPENED", "NULL"));
        assertRejected("result lạ", valid("VACCINE_DUE", "10", "NULL", "NULL", "DONE", "'MAYBE'"));
        assertRejected("cancel_reason 301 ký tự", values("1", "2", "3", "'VACCINE_DUE'", "10", "NULL", "NULL",
                "current_date", "'CANCELLED'", "NULL", "'" + "x".repeat(CareTask.CANCEL_REASON_MAX_LENGTH + 1) + "'"));
        assertAccepted("cancel_reason đúng 300 ký tự", values("1", "2", "3", "'VACCINE_DUE'", "10", "NULL", "NULL",
                "current_date", "'CANCELLED'", "NULL", "'" + "x".repeat(CareTask.CANCEL_REASON_MAX_LENGTH) + "'"));
    }

    /** Tập giá trị trong CHECK của DB phải đúng bằng enum Java: thêm / bớt một bên mà quên bên kia sẽ đỏ ở đây. */
    @Test
    void theCheckListsEqualTheJavaEnums() {
        assertThat(checkValues("ck_care_tasks_task_type")).isEqualTo(names(CareTaskType.values()));
        assertThat(checkValues("ck_care_tasks_status")).isEqualTo(names(CareTaskStatus.values()));
        assertThat(checkValues("ck_care_tasks_result")).isEqualTo(names(CareTaskResult.values()));
    }

    /** Lưu qua entity mọi giá trị enum (kèm kết quả cho {@code DONE}) → CHECK chấp nhận hết, đọc lại đúng giá trị. */
    @Test
    void everyJavaEnumValueIsAcceptedByTheChecksWhenSavedThroughTheEntity() {
        tx().executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            int saved = 0;
            for (CareTaskType type : CareTaskType.values()) {
                for (CareTaskStatus taskStatus : CareTaskStatus.values()) {
                    List<CareTaskResult> results = taskStatus == CareTaskStatus.DONE
                            ? Arrays.asList(CareTaskResult.values()) : Arrays.asList((CareTaskResult) null);
                    for (CareTaskResult result : results) {
                        CareTask task = CareTask.open(new NewCareTask(type, SOURCE.incrementAndGet(), 1L, 2L, 3L,
                                LocalDate.of(2018, 6, 20)));
                        ReflectionTestUtils.setField(task, "status", taskStatus);
                        ReflectionTestUtils.setField(task, "result", result);
                        if (taskStatus == CareTaskStatus.DONE) {
                            ReflectionTestUtils.setField(task, "note", "đã gọi");
                            ReflectionTestUtils.setField(task, "handledBy", 1L);
                            ReflectionTestUtils.setField(task, "handledAt", Instant.parse("2018-06-20T01:02:03Z"));
                        } else if (taskStatus == CareTaskStatus.CANCELLED) {
                            ReflectionTestUtils.setField(task, "cancelReason", "hủy tay");
                            ReflectionTestUtils.setField(task, "handledAt", Instant.parse("2018-06-20T01:02:03Z"));
                        }
                        CareTask stored = repository.saveAndFlush(task);
                        assertThat(jdbc.queryForObject("SELECT task_type || '/' || status || '/' || coalesce(result, '-') "
                                + "FROM care_tasks WHERE id = ?", String.class, stored.getId()))
                                .isEqualTo(type + "/" + taskStatus + "/" + (result == null ? "-" : result));
                        saved++;
                    }
                }
            }
            assertThat(saved).isEqualTo(CareTaskType.values().length * (2 + CareTaskResult.values().length));
            status.setRollbackOnly();
        });
    }

    private Set<String> checkValues(String constraint) {
        String definition = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, constraint);
        Set<String> values = new HashSet<>();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new HashSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }
}
