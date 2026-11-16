package com.petcare.module.shop.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.petcare.module.shop.service.OnlineOrderAutoConfirmService;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.Result;

class OnlineOrderAutoConfirmJobTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private final OnlineOrderAutoConfirmService service = mock(OnlineOrderAutoConfirmService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private OnlineOrderAutoConfirmJob job(int batchSize, Duration budget) {
        return new OnlineOrderAutoConfirmJob(service,
                new OnlineOrderAutoConfirmProperties("40 12 * * * *", batchSize, budget), clock, nanos::get);
    }

    private static DueOrder due(long id, int day) {
        return new DueOrder(id, Instant.parse("2026-11-0" + day + "T03:00:00Z"));
    }

    @Test
    void keysetAdvancesByDeliveredAtThenId() {
        OnlineOrderAutoConfirmJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(5, 1), due(3, 2)));
        when(service.findDue(NOW, due(3, 2).deliveredAt(), 3L, 2)).thenReturn(List.of(due(4, 3)));
        when(service.confirm(anyLong())).thenReturn(Result.CONFIRMED);

        job.run();

        verify(service).confirm(5L);
        verify(service).confirm(3L);
        verify(service).confirm(4L);
        verify(service, never()).findDue(NOW, due(4, 3).deliveredAt(), 4L, 2);
    }

    @Test
    void oneFailingOrderDoesNotStopTheBatchAndIsNotRetriedInTheSameRun() {
        OnlineOrderAutoConfirmJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(1, 1), due(2, 1)));
        when(service.findDue(NOW, due(2, 1).deliveredAt(), 2L, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(service).confirm(1L);
        when(service.confirm(2L)).thenReturn(Result.CONFIRMED);

        job.run();

        verify(service).confirm(2L);
        verify(service).findDue(NOW, due(2, 1).deliveredAt(), 2L, 2);
    }

    @Test
    void skippedOrdersAreCountedAndTheScanContinues() {
        OnlineOrderAutoConfirmJob job = job(5, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(5))).thenReturn(List.of(due(1, 1), due(2, 1)));
        when(service.confirm(1L)).thenReturn(Result.SKIPPED);
        when(service.confirm(2L)).thenReturn(Result.CONFIRMED);

        job.run();

        verify(service).confirm(1L);
        verify(service).confirm(2L);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        OnlineOrderAutoConfirmJob job = job(5, Duration.ofSeconds(1));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(5))).thenReturn(List.of(due(1, 1), due(2, 1), due(3, 1)));
        when(service.confirm(anyLong())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return Result.CONFIRMED;
        });

        job.run();

        verify(service).confirm(1L);
        verify(service).confirm(2L);
        verify(service, never()).confirm(3L);
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findDue(any(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(10, Duration.ofSeconds(1)).run();

        verify(service, never()).confirm(anyLong());
    }
}
