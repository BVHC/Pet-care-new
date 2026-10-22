package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.AppointmentApi.CheckInInfo;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * {@link AppointmentVisitService} (docs/adr/0050): Lịch hẹn#3 / #7 / #8. Mỗi guard có test vi phạm và test biên hợp lệ;
 * mọi nhánh lỗi không ghi gì. Commit / rollback thật: {@code AppointmentVisitTxIT}.
 */
class AppointmentVisitServiceTest {

    private static final long ID = 501L;
    private static final long BRANCH = 3L;
    private static final long CUSTOMER = 7L;
    private static final long PET = 9L;
    private static final long SERVICE = 11L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final LocalTime SLOT = LocalTime.of(9, 0);

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentVisitService service =
            new AppointmentVisitService(appointments, new AppointmentTransitionHandler(), configs);

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES)).thenReturn(30);
        when(configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES)).thenReturn(30);
        when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static Appointment appointment(AppointmentStatus status, long branch, LocalDate date, LocalTime start) {
        Appointment appointment = Appointment.book("LH-261019-0501", CUSTOMER, PET, branch, SERVICE,
                ServiceGroup.MEDICAL, date, start, AppointmentChannel.ONLINE, 1L, null);
        set(appointment, "id", ID);
        set(appointment, "status", status);
        return appointment;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = Appointment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void locked(Appointment appointment) {
        when(appointments.findByIdForUpdate(ID)).thenReturn(Optional.of(appointment));
    }

    /** Mốc {@code slot ± minutes} theo giờ Việt Nam. */
    private static Instant at(LocalDate date, LocalTime time, long plusMinutes) {
        return ZonedDateTime.of(LocalDateTime.of(date, time).plusMinutes(plusMinutes), TimeConfig.BUSINESS_ZONE)
                .toInstant();
    }

    private void assertNothingWritten() {
        verify(appointments, never()).saveAndFlush(any());
    }

    // ---- checkIn (Lịch hẹn#3) -------------------------------------------------------------------------------------

    @Test
    void checkInMovesBookedToCheckedInAndReturnsSnapshot() {
        Appointment appointment = appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT);
        locked(appointment);

        CheckInInfo info = service.checkIn(ID, BRANCH, at(DAY, SLOT, -10));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        assertThat(info).isEqualTo(new CheckInInfo(ID, CUSTOMER, PET, SERVICE, ServiceGroup.MEDICAL, at(DAY, SLOT, 0)));
        verify(appointments).saveAndFlush(appointment);
    }

    /** C1: tham số null là lỗi lập trình, trước mọi đọc. */
    @Test
    void nullArgumentsRejectedBeforeAnyRead() {
        assertThatThrownBy(() -> service.checkIn(null, BRANCH, Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.checkIn(ID, null, Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.completeByVisit(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.cancelByVisit(null, Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.cancelByVisit(ID, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(appointments, configs);
    }

    /** C2: transaction readOnly bị từ chối trước mọi đọc. */
    @Test
    void readOnlyTransactionRejectedBeforeAnyRead() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThatThrownBy(() -> service.completeByVisit(ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThatThrownBy(() -> service.cancelByVisit(ID, Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        verifyNoInteractions(appointments, configs);
    }

    /** C3. */
    @Test
    void missingAppointmentIs404() {
        when(appointments.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, Instant.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertNothingWritten();
    }

    /** C4: chỉ BOOKED có cạnh #3; 409 đứng trước mọi 400 (kể cả khi chi nhánh và giờ cũng sai). */
    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void checkInFromAnyStatusButBookedIs409BeforeAnyRule(AppointmentStatus status) {
        locked(appointment(status, BRANCH + 1, DAY.minusDays(3), SLOT));

        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, at(DAY, SLOT, 0)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("lịch hẹn").hasMessageContaining(status.name());
        assertNothingWritten();
        verifyNoInteractions(configs);
    }

    /** C5. */
    @Test
    void otherBranchIs400() {
        locked(appointment(AppointmentStatus.BOOKED, BRANCH + 1, DAY, SLOT));

        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, at(DAY, SLOT, 0)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TN-02");
        assertNothingWritten();
    }

    /** C6: đúng ngày theo giờ Việt Nam. */
    @Test
    void otherDayIs400() {
        locked(appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT));

        assertRejected(at(DAY.minusDays(1), SLOT, 0));
        assertRejected(at(DAY.plusDays(1), SLOT, 0));
    }

    @Test
    void midnightBoundaryUsesVietnamDate() {
        // Lịch 00:00 ngày D+1; at = 23:59 ngày D, sớm 1 phút ⇒ khác ngày dù nằm trong 30 phút sớm.
        locked(appointment(AppointmentStatus.BOOKED, BRANCH, DAY.plusDays(1), LocalTime.MIDNIGHT));
        assertRejected(at(DAY, LocalTime.of(23, 59), 0));
    }

    /** C7: biên sớm nhất −30 phút bao gồm, −31 phút bị từ chối. */
    @Test
    void earlyBoundaryIsInclusive() {
        Appointment appointment = appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT);
        locked(appointment);

        assertRejected(at(DAY, SLOT, -31));
        service.checkIn(ID, BRANCH, at(DAY, SLOT, -30));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    /** C8: biên trễ nhất +30 phút bao gồm, +31 phút bị từ chối. */
    @Test
    void lateBoundaryIsInclusive() {
        Appointment appointment = appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT);
        locked(appointment);

        assertRejected(at(DAY, SLOT, 31));
        service.checkIn(ID, BRANCH, at(DAY, SLOT, 30));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    /** C9: hai cạnh của cửa sổ đọc từ [CFG], không hằng số. */
    @Test
    void windowFollowsConfig() {
        when(configs.getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES)).thenReturn(10);
        when(configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES)).thenReturn(5);
        Appointment appointment = appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT);
        locked(appointment);

        assertRejected(at(DAY, SLOT, -11));
        assertRejected(at(DAY, SLOT, 6));
        service.checkIn(ID, BRANCH, at(DAY, SLOT, -10));
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    @Test
    void windowMessageNamesTheConfiguredLimits() {
        locked(appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT));

        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, at(DAY, SLOT, -31)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("30 phút trước").hasMessageContaining("30 phút sau");
    }

    private void assertRejected(Instant at) {
        assertThatThrownBy(() -> service.checkIn(ID, BRANCH, at))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TN-02");
        assertNothingWritten();
    }

    /** Khóa dòng trước, rồi mới kiểm; không đọc entity bằng {@code findById} (docs/adr/0029 §4). */
    @Test
    void checkInLocksTheRowBeforeReadingConfigAndWritesLast() {
        Appointment appointment = appointment(AppointmentStatus.BOOKED, BRANCH, DAY, SLOT);
        locked(appointment);

        service.checkIn(ID, BRANCH, at(DAY, SLOT, 0));

        InOrder order = inOrder(appointments, configs);
        order.verify(appointments).findByIdForUpdate(ID);
        order.verify(configs).getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES);
        order.verify(appointments).saveAndFlush(appointment);
        verify(appointments, never()).findById(any());
    }

    // ---- completeByVisit (Lịch hẹn#7) -----------------------------------------------------------------------------

    @Test
    void completeMovesCheckedInToCompleted() {
        Appointment appointment = appointment(AppointmentStatus.CHECKED_IN, BRANCH, DAY, SLOT);
        locked(appointment);

        service.completeByVisit(ID);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        verify(appointments).saveAndFlush(appointment);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"BOOKED", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void completeFromAnyStatusButCheckedInIs409(AppointmentStatus status) {
        locked(appointment(status, BRANCH, DAY, SLOT));

        assertThatThrownBy(() -> service.completeByVisit(ID)).isInstanceOf(InvalidStateTransitionException.class);
        assertNothingWritten();
    }

    @Test
    void completeOfMissingAppointmentIsDataError() {
        when(appointments.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeByVisit(ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("không tồn tại");
        assertNothingWritten();
    }

    // ---- cancelByVisit (Lịch hẹn#8) -------------------------------------------------------------------------------

    @Test
    void cancelByVisitSetsStaffSourceNoLateCancelAndMicrosecondTime() {
        Appointment appointment = appointment(AppointmentStatus.CHECKED_IN, BRANCH, DAY, SLOT);
        locked(appointment);
        Instant at = Instant.parse("2026-10-19T02:03:04.123456789Z");

        service.cancelByVisit(ID, at);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancelSource()).isEqualTo(AppointmentCancelSource.STAFF);
        assertThat(appointment.isLateCancel()).isFalse();
        assertThat(appointment.getCancelReason()).isNull();
        assertThat(appointment.getCancelledAt()).isEqualTo(Instant.parse("2026-10-19T02:03:04.123456Z"));
        verify(appointments).saveAndFlush(appointment);
    }

    /** X3: BOOKED → CANCELLED có trong map FSM nhưng là #4 / #5, không phải #8 — phải bị chốt tường minh. */
    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"BOOKED", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void cancelByVisitFromAnyStatusButCheckedInIs409(AppointmentStatus status) {
        locked(appointment(status, BRANCH, DAY, SLOT));

        assertThatThrownBy(() -> service.cancelByVisit(ID, Instant.now()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(status.name()).hasMessageContaining("CANCELLED");
        assertNothingWritten();
    }

    @Test
    void cancelOfMissingAppointmentIsDataError() {
        when(appointments.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelByVisit(ID, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertNothingWritten();
    }

    // ---- ranh giới transaction ------------------------------------------------------------------------------------

    /** Không lớp nào được tự mở transaction hay nuốt rollback: MANDATORY, không noRollbackFor. */
    @Test
    void everyApiMethodIsMandatoryWithoutNoRollbackFor() throws NoSuchMethodException {
        List<Method> methods = List.of(
                AppointmentVisitService.class.getMethod("checkIn", Long.class, Long.class, Instant.class),
                AppointmentVisitService.class.getMethod("completeByVisit", Long.class),
                AppointmentVisitService.class.getMethod("cancelByVisit", Long.class, Instant.class));

        for (Method method : methods) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.noRollbackFor()).as(method.getName()).isEmpty();
            assertThat(tx.readOnly()).as(method.getName()).isFalse();
        }
    }

    @Test
    void cancelByVisitNeverTouchesRestrictions() {
        // Không có BookingRestrictionApi trong constructor: hủy lượt không phải vi phạm BR-LH-09 (X4).
        assertThat(AppointmentVisitService.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(AppointmentRepository.class, AppointmentTransitionHandler.class, SystemConfigApi.class);
    }
}
