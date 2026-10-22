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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.service.AppointmentNoShowService;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.appointment.service.NoShowCutoff;
import com.petcare.platform.config.TimeConfig;

/** {@link NoShowJob} (docs/adr/0053): keyset, lỗi từng lịch, ngân sách thời gian, không ném khỏi {@code run()}. */
class NoShowJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final NoShowCutoff CUTOFF = new NoShowCutoff(LocalDate.of(2026, 10, 19), LocalTime.of(9, 30));

    private final AppointmentNoShowService service = mock(AppointmentNoShowService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private NoShowJob job(int batchSize, Duration budget) {
        when(service.cutoff(NOW)).thenReturn(CUTOFF);
        return new NoShowJob(service, new NoShowProperties("0 */5 * * * *", batchSize, budget), clock, nanos::get);
    }

    @Test
    void walksBatchesByKeysetAndMarksEach() {
        NoShowJob job = job(2, Duration.ofSeconds(60));
        when(service.findOverdueIds(CUTOFF, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOverdueIds(CUTOFF, 2, 2)).thenReturn(List.of(3L));
        when(service.markNoShow(anyLong(), eq(CUTOFF))).thenReturn(NoShowResult.MARKED);

        job.run();

        verify(service).markNoShow(1L, CUTOFF);
        verify(service).markNoShow(2L, CUTOFF);
        verify(service).markNoShow(3L, CUTOFF);
        verify(service, never()).findOverdueIds(CUTOFF, 3, 2);
    }

    @Test
    void failedAppointmentIsNotRetriedInSameRunAndOthersContinue() {
        NoShowJob job = job(2, Duration.ofSeconds(60));
        when(service.findOverdueIds(CUTOFF, 0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOverdueIds(CUTOFF, 2, 2)).thenReturn(List.of());
        doThrow(new UnsupportedOperationException("D033")).when(service).markNoShow(1L, CUTOFF);
        when(service.markNoShow(2L, CUTOFF)).thenReturn(NoShowResult.MARKED);

        job.run();

        verify(service).markNoShow(2L, CUTOFF);
        verify(service).findOverdueIds(CUTOFF, 2, 2);
    }

    @Test
    void stopsWhenTimeBudgetIsSpent() {
        NoShowJob job = job(5, Duration.ofSeconds(1));
        when(service.findOverdueIds(CUTOFF, 0, 5)).thenReturn(List.of(1L, 2L, 3L));
        when(service.markNoShow(anyLong(), eq(CUTOFF))).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return NoShowResult.MARKED;
        });

        job.run();

        verify(service).markNoShow(1L, CUTOFF);
        verify(service).markNoShow(2L, CUTOFF);
        verify(service, never()).markNoShow(3L, CUTOFF);
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.cutoff(any())).thenThrow(new IllegalStateException("config"));
        NoShowJob job = new NoShowJob(service, new NoShowProperties("0 */5 * * * *", 10, Duration.ofSeconds(1)), clock,
                nanos::get);

        job.run();

        verify(service, never()).findOverdueIds(any(), anyLong(), anyInt());
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = NoShowJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.no-show.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(NoShowJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
