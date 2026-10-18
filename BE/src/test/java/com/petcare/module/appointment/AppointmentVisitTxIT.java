package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.api.AppointmentApi.CheckInInfo;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Commit / rollback của {@code AppointmentApi} theo transaction của Visit#1 / #5 / #6 (docs/adr/0046). {@link VisitLike}
 * đứng thay visit (T26): mở transaction ghi, chèn dòng {@code visits} thật bằng SQL (FK +
 * {@code uq_visits_appointment_id}) rồi gọi {@code AppointmentApi}. Mọi nhánh lỗi phải để lại 0 dòng: so {@code xmin}
 * và số dòng {@code visits} trước / sau.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AppointmentVisitTxIT.TestBeans.class})
class AppointmentVisitTxIT {

    private static final LocalDate DAY = LocalDate.of(2031, 3, 10);
    private static final LocalTime SLOT = LocalTime.of(9, 0);

    /** Bên gọi giả lập: mỗi method là một "use case" của visit, một transaction ghi. */
    static class VisitLike {

        private final AppointmentApi api;
        private final AppointmentItSupport support;

        VisitLike(AppointmentApi api, JdbcTemplate jdbc) {
            this.api = api;
            this.support = new AppointmentItSupport(jdbc);
        }

        /** Visit#1: chèn visit rồi check-in; {@code after} chạy sau (một bước khác của visit). */
        @Transactional
        public CheckInInfo visit1(long appointmentId, long branchId, Instant at, long staff, Runnable after) {
            support.visit(appointmentId, staff);
            CheckInInfo info = api.checkIn(appointmentId, branchId, at);
            after.run();
            return info;
        }

        /** Visit#1 mà bước check-in bị từ chối và bên gọi <b>nuốt</b> lỗi rồi đi tiếp (sai hợp đồng). */
        @Transactional
        public void visit1SwallowingFailure(long appointmentId, long branchId, Instant at, long staff) {
            support.visit(appointmentId, staff);
            try {
                api.checkIn(appointmentId, branchId, at);
            } catch (InvalidStateTransitionException | BusinessRuleViolationException swallowed) {
                // Bắt lỗi của AppointmentApi là vi phạm nghĩa vụ trong javadoc: commit phải ném UnexpectedRollback.
            }
        }

        @Transactional
        public void visit5(long appointmentId, Runnable after) {
            api.completeByVisit(appointmentId);
            after.run();
        }

        @Transactional
        public void visit6(long appointmentId, Instant at, Runnable after) {
            api.cancelByVisit(appointmentId, at);
            after.run();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        VisitLike visitLike(AppointmentApi api, JdbcTemplate jdbc) {
            return new VisitLike(api, jdbc);
        }
    }

    @Autowired
    private VisitLike visit;

    @Autowired
    private AppointmentApi api;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private long branch;
    private long staff;
    private long customer;
    private long pet;
    private long medical;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        staff = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_appointments ON appointments");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_appointments()");
        data.cleanUp();
    }

    private long appointment(String status) {
        return data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, SLOT, status, staff);
    }

    private static Instant at(long minutesFromSlot) {
        return ZonedDateTime.of(LocalDateTime.of(DAY, SLOT).plusMinutes(minutesFromSlot), TimeConfig.BUSINESS_ZONE)
                .toInstant();
    }

    private String xmin(long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM appointments WHERE id = ?", String.class, id);
    }

    private long visits(long appointmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM visits WHERE appointment_id = ?", Long.class, appointmentId);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, cancel_source, late_cancel, cancel_reason, cancelled_at, updated_at
                FROM appointments WHERE id = ?""", id);
    }

    // (1) Visit#1 thành công: visit và lịch cùng commit.
    @Test
    void visit1CommitsVisitAndCheckedInTogether() {
        long id = appointment("BOOKED");
        Object updatedBefore = row(id).get("updated_at");

        CheckInInfo info = visit.visit1(id, branch, at(-5), staff, () -> {
        });

        assertThat(info.appointmentId()).isEqualTo(id);
        assertThat(visits(id)).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "CHECKED_IN");
        assertThat(((java.sql.Timestamp) row(id).get("updated_at")).toInstant())
                .isAfter(((java.sql.Timestamp) updatedBefore).toInstant());
    }

    // (2) Một bước sau check-in lỗi: không còn dòng visits, lịch vẫn BOOKED.
    @Test
    void failureAfterCheckInRollsBackVisitAndAppointment() {
        long id = appointment("BOOKED");
        String xmin = xmin(id);

        assertThatThrownBy(() -> visit.visit1(id, branch, at(0), staff, () -> {
            throw new IllegalStateException("order lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(visits(id)).isZero();
        assertThat(row(id)).containsEntry("status", "BOOKED");
        assertThat(xmin(id)).isEqualTo(xmin);
    }

    // (3) check-in bị từ chối (409 / 400): không dòng nào được ghi, kể cả visit đã chèn trước đó.
    @Test
    void rejectedCheckInLeavesNothing() {
        long cancelled = appointment("CANCELLED");
        jdbc.update("UPDATE appointments SET cancel_source = 'STAFF' WHERE id = ?", cancelled);
        long booked = appointment("BOOKED");
        long otherBranchBooked = data.appointment(customer, data.pet(customer), data.branch("ACTIVE"), medical,
                "MEDICAL", DAY, SLOT, "BOOKED", staff);
        String xminCancelled = xmin(cancelled);
        String xminBooked = xmin(booked);

        assertThatThrownBy(() -> visit.visit1(cancelled, branch, at(0), staff, () -> {
        })).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> visit.visit1(booked, branch, at(-31), staff, () -> {
        })).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TN-02");
        assertThatThrownBy(() -> visit.visit1(otherBranchBooked, branch, at(0), staff, () -> {
        })).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(visits(cancelled) + visits(booked) + visits(otherBranchBooked)).isZero();
        assertThat(xmin(cancelled)).isEqualTo(xminCancelled);
        assertThat(xmin(booked)).isEqualTo(xminBooked);
        assertThat(row(otherBranchBooked)).containsEntry("status", "BOOKED");
    }

    // (4) Visit#5.
    @Test
    void completeThenFailureLeavesCheckedIn() {
        long id = appointment("CHECKED_IN");
        String xmin = xmin(id);

        assertThatThrownBy(() -> visit.visit5(id, () -> {
            throw new IllegalStateException("khóa bệnh án lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(row(id)).containsEntry("status", "CHECKED_IN");
        assertThat(xmin(id)).isEqualTo(xmin);

        visit.visit5(id, () -> {
        });
        assertThat(row(id)).containsEntry("status", "COMPLETED");
    }

    // (5) Visit#6.
    @Test
    void cancelByVisitThenFailureLeavesCheckedInWithNoCancelColumns() {
        long id = appointment("CHECKED_IN");
        String xmin = xmin(id);

        assertThatThrownBy(() -> visit.visit6(id, at(5), () -> {
            throw new IllegalStateException("hủy order lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(row(id)).containsEntry("status", "CHECKED_IN").containsEntry("cancel_source", null)
                .containsEntry("cancelled_at", null).containsEntry("late_cancel", false);
        assertThat(xmin(id)).isEqualTo(xmin);

        visit.visit6(id, at(5), () -> {
        });
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false).containsEntry("cancel_reason", null);
        assertThat(((java.sql.Timestamp) row(id).get("cancelled_at")).toInstant()).isEqualTo(at(5));
    }

    // (6) Bên phát nuốt lỗi của appointment → commit ném UnexpectedRollbackException, không dòng nào được ghi.
    @Test
    void callerSwallowingTheFailureCannotCommit() {
        long id = appointment("COMPLETED");
        String xmin = xmin(id);

        assertThatThrownBy(() -> visit.visit1SwallowingFailure(id, branch, at(0), staff))
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(visits(id)).isZero();
        assertThat(xmin(id)).isEqualTo(xmin);
    }

    // (7) Ngoài transaction.
    @Test
    void callOutsideATransactionIsRefused() {
        long id = appointment("BOOKED");

        assertThatThrownBy(() -> api.checkIn(id, branch, at(0))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.completeByVisit(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.cancelByVisit(id, at(0))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(row(id)).containsEntry("status", "BOOKED");
    }

    // (8) Transaction readOnly.
    @Test
    void readOnlyTransactionIsRefusedBeforeAnyLock() {
        long id = appointment("BOOKED");
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> api.checkIn(id, branch, at(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> api.completeByVisit(id)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> api.cancelByVisit(id, at(0))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(id)).containsEntry("status", "BOOKED");
    }

    /**
     * Lỗi DB lộ ngay trong lời gọi chứ không đợi tới commit của bên phát: trigger tạm từ chối UPDATE
     * {@code appointments}; nếu dùng {@code save} thay {@code saveAndFlush} thì lỗi chỉ hiện ở commit và biến
     * {@code failedInsideCall} vẫn null.
     */
    @Test
    void databaseFailureSurfacesInsideTheCall() {
        long id = appointment("BOOKED");
        jdbc.execute("""
                CREATE FUNCTION it_fail_appointments() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'IT từ chối UPDATE appointments'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER it_fail_appointments BEFORE UPDATE ON appointments
                FOR EACH ROW EXECUTE FUNCTION it_fail_appointments()""");
        AtomicReference<Throwable> failedInsideCall = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            try {
                api.checkIn(id, branch, at(0));
            } catch (RuntimeException e) {
                failedInsideCall.set(e);
                throw e;
            }
        })).isInstanceOf(RuntimeException.class);

        assertThat(failedInsideCall.get()).as("lỗi phải ném từ chính lời gọi checkIn").isNotNull();
        assertThat(row(id)).containsEntry("status", "BOOKED");
    }

    @Test
    void checkInDoesNotTouchRemindedAtOrOtherColumns() {
        long id = appointment("BOOKED");
        jdbc.update("UPDATE appointments SET reminded_at = now(), reschedule_count = 2 WHERE id = ?", id);
        Map<String, Object> before = jdbc.queryForMap("SELECT reminded_at, reschedule_count, slot_start FROM appointments WHERE id = ?", id);

        visit.visit1(id, branch, at(0), staff, () -> {
        });

        assertThat(jdbc.queryForMap("SELECT reminded_at, reschedule_count, slot_start FROM appointments WHERE id = ?", id))
                .isEqualTo(before);
    }
}
