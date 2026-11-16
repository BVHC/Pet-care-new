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

import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.Result;

class OnlineOrderPaymentExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private final OnlineOrderPaymentExpiryService service = mock(OnlineOrderPaymentExpiryService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private OnlineOrderPaymentExpiryJob job(int batchSize, Duration budget) {
        return new OnlineOrderPaymentExpiryJob(service,
                new OnlineOrderPaymentExpiryProperties("20 6/15 * * * *", batchSize, budget), clock, nanos::get);
    }

    private static DueOrder due(long id, int hour) {
        return new DueOrder(id, Instant.parse("2026-11-15T0" + hour + ":00:00Z"));
    }

    @Test
    void keysetAdvancesByDueTimeThenId() {
        OnlineOrderPaymentExpiryJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(5, 1), due(3, 2)));
        when(service.findDue(NOW, due(3, 2).paymentDueAt(), 3L, 2)).thenReturn(List.of(due(4, 3)));
        when(service.expire(anyLong())).thenReturn(Result.EXPIRED);

        job.run();

        verify(service).expire(5L);
        verify(service).expire(3L);
        verify(service).expire(4L);
        verify(service, never()).findDue(NOW, due(4, 3).paymentDueAt(), 4L, 2);
    }

    @Test
    void oneFailingOrderDoesNotStopTheBatchAndIsNotRetriedInTheSameRun() {
        OnlineOrderPaymentExpiryJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(1, 1), due(2, 1)));
        when(service.findDue(NOW, due(2, 1).paymentDueAt(), 2L, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(service).expire(1L);
        when(service.expire(2L)).thenReturn(Result.EXPIRED);

        job.run();

        verify(service).expire(2L);
        verify(service).findDue(NOW, due(2, 1).paymentDueAt(), 2L, 2);
    }

    @Test
    void skippedOrdersAreCountedAndTheScanContinues() {
        OnlineOrderPaymentExpiryJob job = job(5, Duration.ofSeconds(60));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(5))).thenReturn(List.of(due(1, 1), due(2, 1)));
        when(service.expire(1L)).thenReturn(Result.SKIPPED);
        when(service.expire(2L)).thenReturn(Result.EXPIRED);

        job.run();

        verify(service).expire(1L);
        verify(service).expire(2L);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        OnlineOrderPaymentExpiryJob job = job(5, Duration.ofSeconds(1));
        when(service.findDue(eq(NOW), isNull(), eq(0L), eq(5))).thenReturn(List.of(due(1, 1), due(2, 1), due(3, 1)));
        when(service.expire(anyLong())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return Result.EXPIRED;
        });

        job.run();

        verify(service).expire(1L);
        verify(service).expire(2L);
        verify(service, never()).expire(3L);
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findDue(any(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(10, Duration.ofSeconds(1)).run();

        verify(service, never()).expire(anyLong());
    }
}
