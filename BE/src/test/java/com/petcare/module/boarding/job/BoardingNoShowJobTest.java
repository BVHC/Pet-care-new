package com.petcare.module.boarding.job;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.petcare.module.boarding.service.BoardingNoShowService;
import com.petcare.module.boarding.service.BoardingNoShowService.DueBooking;
import com.petcare.module.boarding.service.BoardingNoShowService.NoShowResult;

class BoardingNoShowJobTest {

    private static final Instant NOW = Instant.parse("2009-06-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2009, 6, 10);

    private final BoardingNoShowService service = mock(BoardingNoShowService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private BoardingNoShowJob job(int batchSize, Duration budget) {
        return new BoardingNoShowJob(service, new BoardingNoShowProperties("0 3/5 * * * *", batchSize, budget), clock,
                nanos::get);
    }

    private static DueBooking due(long id, int day) {
        return new DueBooking(id, LocalDate.of(2009, 6, day));
    }

    @Test
    void keysetAdvancesByCheckInDateThenId() {
        BoardingNoShowJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(TODAY), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(5, 8), due(3, 9)));
        when(service.findDue(TODAY, LocalDate.of(2009, 6, 9), 3L, 2)).thenReturn(List.of(due(4, 10)));
        when(service.markNoShow(anyLong())).thenReturn(NoShowResult.MARKED);

        job.run();

        verify(service).markNoShow(5L);
        verify(service).markNoShow(3L);
        verify(service).markNoShow(4L);
        verify(service, never()).findDue(TODAY, LocalDate.of(2009, 6, 10), 4L, 2);
    }

    @Test
    void oneFailingBookingDoesNotStopTheBatchAndIsNotRetriedInTheSameRun() {
        BoardingNoShowJob job = job(2, Duration.ofSeconds(60));
        when(service.findDue(eq(TODAY), isNull(), eq(0L), eq(2))).thenReturn(List.of(due(1, 9), due(2, 9)));
        when(service.findDue(TODAY, LocalDate.of(2009, 6, 9), 2L, 2)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(service).markNoShow(1L);
        when(service.markNoShow(2L)).thenReturn(NoShowResult.MARKED);

        job.run();

        verify(service).markNoShow(2L);
        verify(service).findDue(TODAY, LocalDate.of(2009, 6, 9), 2L, 2);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        BoardingNoShowJob job = job(5, Duration.ofSeconds(1));
        when(service.findDue(eq(TODAY), isNull(), eq(0L), eq(5)))
                .thenReturn(List.of(due(1, 9), due(2, 9), due(3, 9)));
        when(service.markNoShow(anyLong())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return NoShowResult.MARKED;
        });

        job.run();

        verify(service).markNoShow(1L);
        verify(service).markNoShow(2L);
        verify(service, never()).markNoShow(3L);
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findDue(any(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(10, Duration.ofSeconds(1)).run();

        verify(service, never()).markNoShow(anyLong());
    }
}
