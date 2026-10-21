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

import com.petcare.module.sales.repository.SalesOrderRepository.BranchOverdue;
import com.petcare.module.sales.service.PendingOrderDigestService;
import com.petcare.module.sales.service.PendingOverdueCutoff;
import com.petcare.platform.config.TimeConfig;

/** {@link PendingOrderDigestJob} (docs/adr/0064): mỗi chi nhánh một lần gọi, lỗi một chi nhánh không chặn chi nhánh khác. */
class PendingOrderDigestJobTest {

    private static final Instant CUTOFF = Instant.parse("2026-10-20T17:00:00Z");

    private final PendingOrderDigestService service = mock(PendingOrderDigestService.class);
    private final PendingOverdueCutoff cutoff = mock(PendingOverdueCutoff.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T16:50:00Z"), ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();

    private PendingOrderDigestJob job(Duration budget) {
        when(cutoff.cutoff()).thenReturn(CUTOFF);
        when(cutoff.days()).thenReturn(1);
        return new PendingOrderDigestJob(service, cutoff, new PendingOrderDigestProperties("0 50 23 * * *", budget),
                clock, nanos::get);
    }

    private static BranchOverdue overdue(long branch, long count) {
        BranchOverdue row = mock(BranchOverdue.class);
        when(row.getBranchId()).thenReturn(branch);
        when(row.getOrderCount()).thenReturn(count);
        when(row.getOldestPendingAt()).thenReturn(Instant.parse("2026-10-19T03:00:00Z"));
        return row;
    }

    @Test
    void notifiesEveryBranchThatHasOverdueOrders() {
        PendingOrderDigestJob job = job(Duration.ofSeconds(60));
        List<BranchOverdue> rows = List.of(overdue(1L, 2), overdue(2L, 5));
        when(service.findOverdueBranches(CUTOFF)).thenReturn(rows);

        job.run();

        verify(service).notifyPendingOrders(1L, 2, Instant.parse("2026-10-19T03:00:00Z"), 1);
        verify(service).notifyPendingOrders(2L, 5, Instant.parse("2026-10-19T03:00:00Z"), 1);
    }

    @Test
    void failedBranchDoesNotStopTheOthers() {
        PendingOrderDigestJob job = job(Duration.ofSeconds(60));
        List<BranchOverdue> rows = List.of(overdue(1L, 2), overdue(2L, 5));
        when(service.findOverdueBranches(CUTOFF)).thenReturn(rows);
        doThrow(new IllegalStateException("boom")).when(service).notifyPendingOrders(anyLong(), anyLong(), any(),
                anyInt());

        job.run();

        verify(service).notifyPendingOrders(1L, 2, Instant.parse("2026-10-19T03:00:00Z"), 1);
        verify(service).notifyPendingOrders(2L, 5, Instant.parse("2026-10-19T03:00:00Z"), 1);
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        PendingOrderDigestJob job = job(Duration.ofSeconds(1));
        List<BranchOverdue> rows = List.of(overdue(1L, 2), overdue(2L, 5), overdue(3L, 1));
        when(service.findOverdueBranches(CUTOFF)).thenReturn(rows);
        org.mockito.Mockito.doAnswer(call -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return null;
        }).when(service).notifyPendingOrders(anyLong(), anyLong(), any(), anyInt());

        job.run();

        verify(service).notifyPendingOrders(org.mockito.ArgumentMatchers.eq(1L), anyLong(), any(), anyInt());
        verify(service).notifyPendingOrders(org.mockito.ArgumentMatchers.eq(2L), anyLong(), any(), anyInt());
        verify(service, never()).notifyPendingOrders(org.mockito.ArgumentMatchers.eq(3L), anyLong(), any(), anyInt());
    }

    @Test
    void runLevelFailureIsSwallowed() {
        PendingOrderDigestJob job = job(Duration.ofSeconds(60));
        when(service.findOverdueBranches(any())).thenThrow(new IllegalStateException("db"));

        job.run();

        verify(service, never()).notifyPendingOrders(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void jobIsScheduledInBusinessZoneAndNotTransactional() throws Exception {
        Method run = PendingOrderDigestJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.jobs.pending-order-digest.cron}");
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
        assertThat(run.getAnnotation(Transactional.class)).isNull();
        assertThat(PendingOrderDigestJob.class.getAnnotation(Transactional.class)).isNull();
    }
}
