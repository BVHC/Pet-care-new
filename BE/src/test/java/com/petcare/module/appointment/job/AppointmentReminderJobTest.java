package com.petcare.module.appointment.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.service.AppointmentReminderService;
import com.petcare.module.appointment.service.AppointmentReminderService.ReminderResult;
import com.petcare.module.appointment.service.ReminderWindow;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link AppointmentReminderJob} (docs/adr/0051): keyset, một cửa sổ cho cả lượt, lỗi từng lịch, ngân sách thời gian,
 * không ném khỏi {@code run()}, đếm theo kết quả.
 */
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final ReminderWindow WINDOW = ReminderWindow.at(NOW, 24);

    private final AppointmentReminderService service = mock(AppointmentReminderService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private AppointmentReminderJob job(int batchSize, Duration budget) {
        when(service.window(NOW)).thenReturn(WINDOW);
        return new AppointmentReminderJob(service,
                new AppointmentReminderProperties("0 2/5 * * * *", batchSize, budget), clock, nanos::get);
    }

    @Test
    void walksBatchesByKeysetWithOneWindowAndCountsResults(CapturedOutput output) {
        AppointmentReminderJob job = job(2, Duration.ofSeconds(60));
        when(service.findDueIds(WINDOW, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findDueIds(WINDOW, 2, 2)).thenReturn(List.of(3L));
        when(service.remind(1L, WINDOW)).thenReturn(ReminderResult.REMINDED);
        when(service.remind(2L, WINDOW)).thenReturn(ReminderResult.NOT_SENT);
        when(service.remind(3L, WINDOW)).thenReturn(ReminderResult.SKIPPED);

        job.run();

        verify(service).window(NOW);
        verify(service, never()).findDueIds(WINDOW, 3, 2);
        assertThat(output)
                .contains("APPOINTMENT_REMINDER reminded=1 notSent=1 skipped=1 failed=0 stoppedByBudget=false");
    }

    @Test
    void failedAppointmentIsNotRetriedInSameRunAndOthersContinue(CapturedOutput output) {
        AppointmentReminderJob job = job(2, Duration.ofSeconds(60));
        when(service.findDueIds(WINDOW, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findDueIds(WINDOW, 2, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("dữ liệu hỏng")).when(service).remind(1L, WINDOW);
        when(service.remind(2L, WINDOW)).thenReturn(ReminderResult.REMINDED);

        job.run();

        verify(service).remind(2L, WINDOW);
        verify(service).findDueIds(WINDOW, 2, 2);
        assertThat(output).contains("APPOINTMENT_REMINDER_FAILED appointmentId=1 exception=IllegalStateException")
                .contains("reminded=1 notSent=0 skipped=0 failed=1");
    }

    @Test
    void stopsWhenTimeBudgetIsSpent() {
        AppointmentReminderJob job = job(5, Duration.ofSeconds(1));
        when(service.findDueIds(WINDOW, 0, 5)).thenReturn(List.of(1L, 2L, 3L));
        when(service.remind(anyLong(), eq(WINDOW))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return ReminderResult.REMINDED;
        });

        job.run();

        verify(service).remind(1L, WINDOW);
        verify(service).remind(2L, WINDOW);
        verify(service, never()).remind(3L, WINDOW);
    }

    @Test
    void runLevelFailureIsSwallowed(CapturedOutput output) {
        when(service.window(any())).thenThrow(new IllegalStateException("config"));
        AppointmentReminderJob job = new AppointmentReminderJob(service,
                new AppointmentReminderProperties("0 2/5 * * * *", 10, Duration.ofSeconds(1)), clock, nanos::get);

        job.run();

        verify(service, never()).findDueIds(any(), anyLong(), anyInt());
        assertThat(output).contains("APPOINTMENT_REMINDER_FAILED remindedSoFar=0");
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = AppointmentReminderJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.appointment-reminder.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(AppointmentReminderJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
