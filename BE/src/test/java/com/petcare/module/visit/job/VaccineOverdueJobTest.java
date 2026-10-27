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

import com.petcare.module.visit.service.ReminderResult;
import com.petcare.module.visit.service.VaccineOverdueService;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link VaccineOverdueJob} (docs/adr/0083): keyset, một ngưỡng cho cả lượt, lỗi từng mũi không chặn mũi sau, ngân sách
 * thời gian, không ném khỏi {@code run()}, đếm theo kết quả.
 */
@ExtendWith(OutputCaptureExtension.class)
class VaccineOverdueJobTest {

    private static final LocalDate CUTOFF = LocalDate.of(2026, 10, 20);

    private final VaccineOverdueService service = mock(VaccineOverdueService.class);
    private final AtomicLong nanos = new AtomicLong();

    private VaccineOverdueJob job(int batchSize, Duration budget) {
        when(service.cutoff()).thenReturn(CUTOFF);
        return new VaccineOverdueJob(service, new VaccineOverdueProperties("30 25 7-20 * * *", batchSize, budget),
                nanos::get);
    }

    @Test
    void walksBatchesByKeysetWithOneCutoffAndCountsResults(CapturedOutput output) {
        VaccineOverdueJob job = job(2, Duration.ofSeconds(60));
        when(service.findOverdueIds(CUTOFF, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOverdueIds(CUTOFF, 2, 2)).thenReturn(List.of(3L));
        when(service.raise(1L, CUTOFF)).thenReturn(ReminderResult.TASK_CREATED);
        when(service.raise(2L, CUTOFF)).thenReturn(ReminderResult.NOT_SENT);
        when(service.raise(3L, CUTOFF)).thenReturn(ReminderResult.SKIPPED);

        job.run();

        verify(service).cutoff();
        assertThat(output).contains("VACCINE_OVERDUE created=1 notCreated=1 skipped=1 failed=0 stoppedByBudget=false");
    }

    @Test
    void failedDoseIsNotRetriedInSameRunAndOthersContinue(CapturedOutput output) {
        VaccineOverdueJob job = job(2, Duration.ofSeconds(60));
        when(service.findOverdueIds(CUTOFF, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOverdueIds(CUTOFF, 2, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("dữ liệu hỏng")).when(service).raise(1L, CUTOFF);
        when(service.raise(2L, CUTOFF)).thenReturn(ReminderResult.TASK_CREATED);

        job.run();

        verify(service).raise(2L, CUTOFF);
        verify(service).findOverdueIds(CUTOFF, 2, 2);
        assertThat(output).contains("VACCINE_OVERDUE_FAILED vaccinationId=1 exception=IllegalStateException")
                .contains("created=1 notCreated=0 skipped=0 failed=1");
    }

    /** Mũi lỗi vĩnh viễn nằm đầu keyset: {@code lastId} vẫn tiến qua nó nên lô sau không đọc lại chính nó. */
    @Test
    void aPermanentlyFailingDoseDoesNotBlockTheNextBatch() {
        VaccineOverdueJob job = job(1, Duration.ofSeconds(60));
        when(service.findOverdueIds(CUTOFF, 0, 1)).thenReturn(List.of(1L));
        when(service.findOverdueIds(CUTOFF, 1, 1)).thenReturn(List.of(2L));
        when(service.findOverdueIds(CUTOFF, 2, 1)).thenReturn(List.of());
        doThrow(new IllegalStateException("dữ liệu hỏng")).when(service).raise(1L, CUTOFF);
        when(service.raise(2L, CUTOFF)).thenReturn(ReminderResult.TASK_CREATED);

        job.run();

        verify(service).raise(2L, CUTOFF);
    }

    @Test
    void stopsWhenTimeBudgetIsSpent(CapturedOutput output) {
        VaccineOverdueJob job = job(5, Duration.ofSeconds(1));
        when(service.findOverdueIds(CUTOFF, 0, 5)).thenReturn(List.of(1L, 2L, 3L));
        when(service.raise(anyLong(), eq(CUTOFF))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return ReminderResult.TASK_CREATED;
        });

        job.run();

        verify(service).raise(1L, CUTOFF);
        verify(service).raise(2L, CUTOFF);
        verify(service, never()).raise(3L, CUTOFF);
        assertThat(output).contains("stoppedByBudget=true");
    }

    @Test
    void runLevelFailureIsSwallowed(CapturedOutput output) {
        when(service.cutoff()).thenThrow(new IllegalStateException("config"));
        VaccineOverdueJob job = new VaccineOverdueJob(service,
                new VaccineOverdueProperties("30 25 7-20 * * *", 10, Duration.ofSeconds(1)), nanos::get);

        job.run();

        verify(service, never()).findOverdueIds(eq(CUTOFF), anyLong(), anyInt());
        assertThat(output).contains("VACCINE_OVERDUE_FAILED createdSoFar=0");
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = VaccineOverdueJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.vaccine-overdue.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(VaccineOverdueJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
