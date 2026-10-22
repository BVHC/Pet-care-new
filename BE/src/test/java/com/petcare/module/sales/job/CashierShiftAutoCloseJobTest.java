package com.petcare.module.sales.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.sales.service.CashierShiftAutoCloseService;
import com.petcare.module.sales.service.CashierShiftAutoCloseService.Result;
import com.petcare.platform.config.TimeConfig;

/** {@link CashierShiftAutoCloseJob} (docs/adr/0064): keyset, lỗi từng ca, ngân sách thời gian, không ném khỏi {@code run()}. */
class CashierShiftAutoCloseJobTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final CashierShiftAutoCloseService service = mock(CashierShiftAutoCloseService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private CashierShiftAutoCloseJob job(int batchSize, Duration budget) {
        return new CashierShiftAutoCloseJob(service,
                new CashierShiftAutoCloseProperties("0 4/5 * * * *", batchSize, budget), clock, nanos::get);
    }

    @Test
    void walksBatchesByKeysetAndClosesEachShiftWithTheRunStartTime() {
        CashierShiftAutoCloseJob job = job(2, Duration.ofSeconds(60));
        when(service.findOpenIds(0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOpenIds(2, 2)).thenReturn(List.of(3L));
        when(service.autoCloseCashierShift(anyLong(), any())).thenReturn(Result.CLOSED);

        job.run();

        verify(service).autoCloseCashierShift(1L, NOW);
        verify(service).autoCloseCashierShift(2L, NOW);
        verify(service).autoCloseCashierShift(3L, NOW);
        verify(service, never()).findOpenIds(3, 2);
    }

    @Test
    void failedShiftIsNotRetriedInTheSameRunAndOthersContinue() {
        CashierShiftAutoCloseJob job = job(2, Duration.ofSeconds(60));
        when(service.findOpenIds(0, 2)).thenReturn(List.of(1L, 2L));
        when(service.findOpenIds(2, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(service).autoCloseCashierShift(1L, NOW);
        when(service.autoCloseCashierShift(2L, NOW)).thenReturn(Result.NOT_DUE);

        job.run();

        verify(service).autoCloseCashierShift(2L, NOW);
        verify(service).findOpenIds(2, 2);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        CashierShiftAutoCloseJob job = job(5, Duration.ofSeconds(1));
        when(service.findOpenIds(0, 5)).thenReturn(List.of(1L, 2L, 3L));
        when(service.autoCloseCashierShift(anyLong(), any())).thenAnswer(call -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return Result.CLOSED;
        });

        job.run();

        verify(service).autoCloseCashierShift(1L, NOW);
        verify(service).autoCloseCashierShift(2L, NOW);
        verify(service, never()).autoCloseCashierShift(3L, NOW);
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findOpenIds(anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(10, Duration.ofSeconds(1)).run();

        verify(service, never()).autoCloseCashierShift(anyLong(), any());
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = CashierShiftAutoCloseJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.cashier-shift-auto-close.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(CashierShiftAutoCloseJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
