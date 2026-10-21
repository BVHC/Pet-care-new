package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.ReminderRow;
import com.petcare.module.appointment.service.AppointmentReminderNotices.Outcome;
import com.petcare.module.appointment.service.AppointmentReminderService.ReminderResult;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * {@link AppointmentReminderService} (docs/adr/0056): thứ tự khóa → đọc → {@code now} → UPDATE có điều kiện → outbox;
 * mọi nhánh bỏ qua không ghi gì; ranh giới transaction; không bao giờ ghi qua entity (Q7).
 */
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderServiceTest {

    private static final long ID = 801L;
    /** Lúc bắt đầu lượt: 2026-10-19 09:00 giờ Việt Nam. */
    private static final Instant RUN_START = Instant.parse("2026-10-19T02:00:00Z");
    private static final ReminderWindow WINDOW = ReminderWindow.at(RUN_START, 24);
    /** {@code now} sau khóa, 10 giây sau đầu lượt, có nano để kiểm cắt về µs. */
    private static final Instant NOW = Instant.parse("2026-10-19T02:00:10.123456789Z");
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 20);

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final AppointmentReminderNotices notices = mock(AppointmentReminderNotices.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentReminderService service = new AppointmentReminderService(appointments, notices, configs,
            Clock.fixed(NOW, ZoneOffset.UTC));

    record Row(Long getId, String getCode, Long getCustomerId, Long getPetId, Long getServiceId, Long getBranchId,
               LocalDate getSlotDate, LocalTime getSlotStart) implements ReminderRow {}

    private static Row row(LocalDate date, LocalTime start) {
        return new Row(ID, "LH-261019-0801", 7L, 9L, 11L, 3L, date, start);
    }

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private void lockSucceeds(Row row) {
        when(appointments.lockDueForReminder(ID, WINDOW.fromDate(), WINDOW.fromTime(), WINDOW.toDate(),
                WINDOW.toTime())).thenReturn(Optional.of(ID));
        when(appointments.findReminderRow(ID)).thenReturn(Optional.of(row));
    }

    @Test
    void windowReadsConfigOnce() {
        when(configs.getInt(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE)).thenReturn(30);

        assertThat(service.window(RUN_START)).isEqualTo(ReminderWindow.at(RUN_START, 30));
        verify(configs).getInt(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE);
    }

    @Test
    void findDueIdsPicksFirstOrNextBatchQuery() {
        when(appointments.findDueForReminderIds(WINDOW.fromDate(), "09:00", WINDOW.toDate(), "09:00", 50))
                .thenReturn(List.of(1L));
        when(appointments.findDueForReminderIdsAfter(WINDOW.fromDate(), "09:00", WINDOW.toDate(), "09:00", 1L, 50))
                .thenReturn(List.of());

        assertThat(service.findDueIds(WINDOW, 0, 50)).containsExactly(1L);
        assertThat(service.findDueIds(WINDOW, 1L, 50)).isEmpty();
    }

    @Test
    void remindsInOrderLockReadNowUpdateEnqueue() {
        Row row = row(LocalDate.of(2026, 10, 19), LocalTime.of(14, 0));
        lockSucceeds(row);
        Instant at = NOW.truncatedTo(ChronoUnit.MICROS);
        when(appointments.markRemindedIfDue(ID, at)).thenReturn(1);
        when(notices.enqueueFor(row)).thenReturn(Outcome.SENT);

        assertThat(service.remind(ID, WINDOW)).isEqualTo(ReminderResult.REMINDED);

        InOrder order = inOrder(appointments, notices);
        order.verify(appointments).lockDueForReminder(ID, WINDOW.fromDate(), WINDOW.fromTime(), WINDOW.toDate(),
                WINDOW.toTime());
        order.verify(appointments).findReminderRow(ID);
        order.verify(appointments).markRemindedIfDue(ID, at);
        order.verify(notices).enqueueFor(row);
        verify(appointments, never()).save(any());
        verify(appointments, never()).saveAndFlush(any());
        verify(appointments, never()).findById(anyLong());
    }

    @Test
    void lockMissIsSkippedWithoutAnyRead() {
        when(appointments.lockDueForReminder(ID, WINDOW.fromDate(), WINDOW.fromTime(), WINDOW.toDate(),
                WINDOW.toTime())).thenReturn(Optional.empty());

        assertThat(service.remind(ID, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verify(appointments, never()).findReminderRow(anyLong());
        verify(appointments, never()).markRemindedIfDue(anyLong(), any());
        verifyNoInteractions(notices);
    }

    /** Lỗ hổng 2 của bản kế hoạch đầu: lịch bắt đầu giữa lượt (sau đầu lượt, trước {@code now} sau khóa) không nhắc. */
    @Test
    void slotThatStartedDuringTheRunIsSkippedWithoutWriting() {
        lockSucceeds(row(LocalDate.of(2026, 10, 19), LocalTime.of(9, 0, 5)));

        assertThat(service.remind(ID, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verify(appointments, never()).markRemindedIfDue(anyLong(), any());
        verifyNoInteractions(notices);
    }

    @Test
    void rowOutsideWindowUnderLockIsCorruptState() {
        lockSucceeds(row(TOMORROW, LocalTime.of(9, 30)));

        assertThatThrownBy(() -> service.remind(ID, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ngoài cửa sổ");
        verify(appointments, never()).markRemindedIfDue(anyLong(), any());
        verifyNoInteractions(notices);
    }

    @Test
    void updateNotHittingExactlyOneRowFailsBeforeEnqueue() {
        lockSucceeds(row(TOMORROW, LocalTime.of(9, 0)));
        when(appointments.markRemindedIfDue(any(Long.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.remind(ID, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 dòng");
        verifyNoInteractions(notices);
    }

    @Test
    void noChannelAndDeceasedPetAreMarkedButNotSent(CapturedOutput output) {
        Row row = row(TOMORROW, LocalTime.of(8, 0));
        lockSucceeds(row);
        when(appointments.markRemindedIfDue(any(Long.class), any())).thenReturn(1);
        when(notices.enqueueFor(row)).thenReturn(Outcome.NO_CHANNEL, Outcome.PET_DECEASED);

        assertThat(service.remind(ID, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);
        assertThat(service.remind(ID, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);

        assertThat(output).contains("APPOINTMENT_REMINDER_NO_CHANNEL appointmentId=801 customerId=7")
                .contains("APPOINTMENT_REMINDER_PET_DECEASED appointmentId=801 petId=9");
    }

    @Test
    void notificationFailurePropagatesSoTheMarkRollsBack() {
        Row row = row(TOMORROW, LocalTime.of(8, 0));
        lockSucceeds(row);
        when(appointments.markRemindedIfDue(any(Long.class), any())).thenReturn(1);
        when(notices.enqueueFor(row)).thenThrow(new IllegalStateException("outbox"));

        assertThatThrownBy(() -> service.remind(ID, WINDOW)).hasMessage("outbox");
    }

    @Test
    void readOnlyTransactionIsRejectedBeforeLocking() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.remind(ID, WINDOW)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments, notices);
    }

    @Test
    void transactionBoundaries() throws Exception {
        Method remind = AppointmentReminderService.class.getMethod("remind", long.class, ReminderWindow.class);
        Transactional tx = remind.getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();

        Method find = AppointmentReminderService.class.getMethod("findDueIds", ReminderWindow.class, long.class,
                int.class);
        assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(AppointmentReminderService.class.getMethod("window", Instant.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}
