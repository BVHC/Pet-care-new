package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskResult;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.repository.CareTaskRepository;

/**
 * Ánh xạ {@link CareTask} ↔ {@code care_tasks} (docs/adr/0079): đủ 17 cột của V1 với <b>giá trị khác nhau từng cột</b>
 * (ánh xạ nhầm cột phải đỏ), và nhóm cột định danh {@code updatable = false} nên một lần lưu entity không ghi đè
 * {@code customer_id} mà luồng chuyển chủ đã đổi bằng UPDATE hàng loạt (06 §8 Q1). Chèn với
 * {@code session_replication_role = replica} nên id cha là giả.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareTaskPersistenceIT {

    @Autowired
    private CareTaskRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private CareTaskItSupport data;
    private TransactionTemplate tx;
    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        data = new CareTaskItSupport(jdbc, transactionManager);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        for (Long id : ids) {
            jdbc.update("DELETE FROM care_tasks WHERE id = ?", id);
        }
        ids.clear();
    }

    private long insert(String type, String sourceColumn, long sourceId, String status, String result, String note,
            String reason, Long handledBy, Instant handledAt, long branch, long customer, long pet, LocalDate due) {
        long id = data.replica(() -> jdbc.queryForObject("""
                INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, %s, due_date, status, result, note,
                                        cancel_reason, handled_by, handled_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2018-01-02T03:04:05Z', '2018-01-03T04:05:06Z')
                RETURNING id""".formatted(sourceColumn), Long.class, branch, customer, pet, type, sourceId, due, status,
                result, note, reason, handledBy, handledAt == null ? null : Timestamp.from(handledAt)));
        ids.add(id);
        return id;
    }

    @Test
    void everyColumnIsMappedToItsOwnField() {
        Instant handledAt = Instant.parse("2018-02-03T04:05:06Z");
        long done = insert("VACCINE_OVERDUE", "vaccination_id", 4101L, "DONE", "UNREACHABLE", "Ghi chú đã gọi", null, 4601L,
                handledAt, 4001L, 4002L, 4003L, LocalDate.of(2018, 7, 8));
        long cancelled = insert("FOLLOW_UP_DUE", "medical_record_id", 4201L, "CANCELLED", null, null, "Lý do hủy tay", 4602L,
                handledAt, 5001L, 5002L, 5003L, LocalDate.of(2018, 8, 9));
        long open = insert("PICKUP_OVERDUE", "boarding_booking_id", 4301L, "OPEN", null, null, null, null, null, 6001L,
                6002L, 6003L, LocalDate.of(2018, 9, 10));

        tx.executeWithoutResult(status -> {
            CareTask a = repository.findById(done).orElseThrow();
            assertThat(a.getId()).isEqualTo(done);
            assertThat(a.getBranchId()).isEqualTo(4001L);
            assertThat(a.getCustomerId()).isEqualTo(4002L);
            assertThat(a.getPetId()).isEqualTo(4003L);
            assertThat(a.getTaskType()).isEqualTo(CareTaskType.VACCINE_OVERDUE);
            assertThat(a.getVaccinationId()).isEqualTo(4101L);
            assertThat(a.getMedicalRecordId()).isNull();
            assertThat(a.getBoardingBookingId()).isNull();
            assertThat(a.getDueDate()).isEqualTo(LocalDate.of(2018, 7, 8));
            assertThat(a.getStatus()).isEqualTo(CareTaskStatus.DONE);
            assertThat(a.getResult()).isEqualTo(CareTaskResult.UNREACHABLE);
            assertThat(a.getNote()).isEqualTo("Ghi chú đã gọi");
            assertThat(a.getCancelReason()).isNull();
            assertThat(a.getHandledBy()).isEqualTo(4601L);
            assertThat(a.getHandledAt()).isEqualTo(handledAt);
            assertThat(a.getCreatedAt()).isEqualTo(Instant.parse("2018-01-02T03:04:05Z"));
            assertThat(a.getUpdatedAt()).isEqualTo(Instant.parse("2018-01-03T04:05:06Z"));

            CareTask b = repository.findById(cancelled).orElseThrow();
            assertThat(b.getTaskType()).isEqualTo(CareTaskType.FOLLOW_UP_DUE);
            assertThat(b.getMedicalRecordId()).isEqualTo(4201L);
            assertThat(b.getVaccinationId()).isNull();
            assertThat(b.getBoardingBookingId()).isNull();
            assertThat(b.getStatus()).isEqualTo(CareTaskStatus.CANCELLED);
            assertThat(b.getResult()).isNull();
            assertThat(b.getCancelReason()).isEqualTo("Lý do hủy tay");
            assertThat(b.getHandledBy()).isEqualTo(4602L);

            CareTask c = repository.findById(open).orElseThrow();
            assertThat(c.getTaskType()).isEqualTo(CareTaskType.PICKUP_OVERDUE);
            assertThat(c.getBoardingBookingId()).isEqualTo(4301L);
            assertThat(c.getStatus()).isEqualTo(CareTaskStatus.OPEN);
            assertThat(c.getHandledBy()).isNull();
            assertThat(c.getHandledAt()).isNull();
        });
    }

    @Test
    void savingTheEntityNeverOverwritesTheIdentityColumnsAChangeOfOwnerUpdatedMeanwhile() {
        long id = insert("VACCINE_DUE", "vaccination_id", 4101L, "OPEN", null, null, null, null, null, 4001L, 4002L,
                4003L, LocalDate.of(2018, 7, 8));

        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");     // chủ mới 9999 là id giả
            CareTask task = repository.findById(id).orElseThrow();          // đang giữ chủ cũ 4002
            // luồng chuyển chủ (06 §8 Q1) đổi customer_id bằng UPDATE hàng loạt, không qua entity
            jdbc.update("UPDATE care_tasks SET customer_id = 9999 WHERE id = ?", id);
            // entity bị bẩn ở cột xử lý (phần 1 sẽ có phương thức làm việc này) rồi flush → Hibernate UPDATE
            ReflectionTestUtils.setField(task, "status", CareTaskStatus.CANCELLED);
            ReflectionTestUtils.setField(task, "cancelReason", "Lý do");
            // V22 (docs/adr/0081): CANCELLED phải có handled_at (ck_care_tasks_cancelled_reason)
            ReflectionTestUtils.setField(task, "handledAt", Instant.parse("2018-02-03T04:05:06Z"));
            repository.saveAndFlush(task);
        });

        assertThat(jdbc.queryForObject("SELECT customer_id FROM care_tasks WHERE id = ?", Long.class, id))
                .as("customer_id chỉ do UPDATE hàng loạt đổi").isEqualTo(9999L);
        assertThat(jdbc.queryForObject("SELECT status FROM care_tasks WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM care_tasks WHERE id = ?", String.class, id))
                .isEqualTo("Lý do");
    }
}
