package com.petcare.module.boarding.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.boarding.service.BoardingOverdueService;
import com.petcare.module.boarding.service.BoardingOverdueService.DueRow;
import com.petcare.module.boarding.service.BoardingOverdueService.FollowUpResult;
import com.petcare.module.boarding.service.BoardingOverdueService.OverdueResult;

class BoardingOverdueJobTest {

    private static final Instant NOW = Instant.parse("2009-06-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2009, 6, 10);

    private final BoardingOverdueService service = mock(BoardingOverdueService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private BoardingOverdueJob job(int batchSize, Duration budget) {
        return new BoardingOverdueJob(service, new BoardingOverdueProperties("0 1/5 * * * *", batchSize, budget), clock,
                nanos::get);
    }

    private static DueRow due(long id, int day) {
        return new DueRow(id, 100 + id, LocalDate.of(2009, 6, day));
    }

    private static DueRow overdue(long id) {
        return new DueRow(id, 100 + id, null);
    }

    @Test
    void firstPassKeysetAdvancesByCheckOutDateThenIdThenSecondPassFollowsUp() {
        BoardingOverdueJob job = job(2, Duration.ofSeconds(60));
        when(service.findCheckedInDue(eq(TODAY), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(5, 8), due(3, 9)));
        when(service.findCheckedInDue(TODAY, LocalDate.of(2009, 6, 9), 3L, 2)).thenReturn(List.of(due(4, 10)));
        when(service.markOverdue(anyLong(), anyLong())).thenReturn(OverdueResult.MARKED);
        when(service.findOverdue(0L, 2)).thenReturn(List.of(overdue(7)));
        when(service.followUp(anyLong(), anyLong())).thenReturn(FollowUpResult.UPDATED);

        job.run();

        InOrder order = inOrder(service);
        order.verify(service).markOverdue(5L, 105L);
        order.verify(service).markOverdue(3L, 103L);
        order.verify(service).markOverdue(4L, 104L);
        order.verify(service).followUp(7L, 107L);
        verify(service, never()).findOverdue(7L, 2);
    }

    @Test
    void oneFailingBookingDoesNotStopTheBatchInEitherPass() {
        BoardingOverdueJob job = job(5, Duration.ofSeconds(60));
        when(service.findCheckedInDue(eq(TODAY), isNull(), eq(0L), eq(5))).thenReturn(List.of(due(1, 9), due(2, 9)));
        doThrow(new IllegalStateException("boom")).when(service).markOverdue(1L, 101L);
        when(service.markOverdue(2L, 102L)).thenReturn(OverdueResult.MARKED);
        when(service.findOverdue(0L, 5)).thenReturn(List.of(overdue(8), overdue(9)));
        doThrow(new IllegalStateException("boom")).when(service).followUp(8L, 108L);
        when(service.followUp(9L, 109L)).thenReturn(FollowUpResult.NOTHING);

        job.run();

        verify(service).markOverdue(2L, 102L);
        verify(service).followUp(9L, 109L);
    }

    @Test
    void stopsBothPassesWhenTheTimeBudgetIsSpent() {
        BoardingOverdueJob job = job(5, Duration.ofSeconds(1));
        when(service.findCheckedInDue(eq(TODAY), isNull(), eq(0L), eq(5)))
                .thenReturn(List.of(due(1, 9), due(2, 9), due(3, 9)));
        when(service.markOverdue(anyLong(), anyLong())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return OverdueResult.MARKED;
        });

        job.run();

        verify(service).markOverdue(1L, 101L);
        verify(service).markOverdue(2L, 102L);
        verify(service, never()).markOverdue(3L, 103L);
        verify(service, never()).findOverdue(anyLong(), anyInt());
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findCheckedInDue(any(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(10, Duration.ofSeconds(1)).run();

        verify(service, never()).markOverdue(anyLong(), anyLong());
    }
}
