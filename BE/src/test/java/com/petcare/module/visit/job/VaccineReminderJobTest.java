package com.petcare.module.visit.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.service.DueDateWindow;
import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineReminderService;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link VaccineReminderJob} (docs/adr/0082): keyset, một cửa sổ cho cả lượt, lỗi từng mũi, ngân sách thời gian, không
 * ném khỏi {@code run()}, đếm theo kết quả.
 */
@ExtendWith(OutputCaptureExtension.class)
class VaccineReminderJobTest {

    private static final DueDateWindow WINDOW = DueDateWindow.at(LocalDate.of(2026, 10, 27), 7);

    private final VaccineReminderService service = mock(VaccineReminderService.class);
    private final AtomicLong nanos = new AtomicLong();

    private VaccineReminderJob job(int batchSize, Duration budget) {
        when(service.window()).thenReturn(WINDOW);
        return new VaccineReminderJob(service, new VaccineReminderProperties("30 10 7-20 * * *", batchSize, budget),
                nanos::get);
    }

    @Test
    void walksBatchesByKeysetWithOneWindowAndCountsResults(CapturedOutput output) {
        VaccineReminderJob job = job(2, Duration.ofSeconds(60));
        when(service.findDueIds(WINDOW, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findDueIds(WINDOW, 2, 2)).thenReturn(List.of(3L, 4L));
        when(service.findDueIds(WINDOW, 4, 2)).thenReturn(List.of());
        when(service.remind(1L, WINDOW)).thenReturn(ReminderResult.REMINDED);
        when(service.remind(2L, WINDOW)).thenReturn(ReminderResult.TASK_CREATED);
        when(service.remind(3L, WINDOW)).thenReturn(ReminderResult.NOT_SENT);
        when(service.remind(4L, WINDOW)).thenReturn(ReminderResult.SKIPPED);

        job.run();

        verify(service).window();
        assertThat(output).contains(
                "VACCINE_REMINDER reminded=1 tasks=1 notSent=1 skipped=1 failed=0 stoppedByBudget=false");
    }

    @Test
    void failedDoseIsNotRetriedInSameRunAndOthersContinue(CapturedOutput output) {
        VaccineReminderJob job = job(2, Duration.ofSeconds(60));
        when(service.findDueIds(WINDOW, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findDueIds(WINDOW, 2, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("dữ liệu hỏng")).when(service).remind(1L, WINDOW);
        when(service.remind(2L, WINDOW)).thenReturn(ReminderResult.REMINDED);

        job.run();

        verify(service).remind(2L, WINDOW);
        verify(service).findDueIds(WINDOW, 2, 2);
        assertThat(output).contains("VACCINE_REMINDER_FAILED vaccinationId=1 exception=IllegalStateException")
                .contains("reminded=1 tasks=0 notSent=0 skipped=0 failed=1");
    }

    @Test
    void stopsWhenTimeBudgetIsSpent(CapturedOutput output) {
        VaccineReminderJob job = job(5, Duration.ofSeconds(1));
        when(service.findDueIds(WINDOW, 0, 5)).thenReturn(List.of(1L, 2L, 3L));
        when(service.remind(anyLong(), eq(WINDOW))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return ReminderResult.REMINDED;
        });

        job.run();

        verify(service).remind(1L, WINDOW);
        verify(service).remind(2L, WINDOW);
        verify(service, never()).remind(3L, WINDOW);
        assertThat(output).contains("stoppedByBudget=true");
    }

    @Test
    void runLevelFailureIsSwallowed(CapturedOutput output) {
        when(service.window()).thenThrow(new IllegalStateException("config"));
        VaccineReminderJob job = new VaccineReminderJob(service,
                new VaccineReminderProperties("30 10 7-20 * * *", 10, Duration.ofSeconds(1)), nanos::get);

        job.run();

        verify(service, never()).findDueIds(eq(WINDOW), anyLong(), anyInt());
        assertThat(output).contains("VACCINE_REMINDER_FAILED remindedSoFar=0");
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = VaccineReminderJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.vaccine-reminder.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(VaccineReminderJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
