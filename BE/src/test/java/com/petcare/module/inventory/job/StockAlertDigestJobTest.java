package com.petcare.module.inventory.job;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.inventory.service.StockAlertDigestService;

class StockAlertDigestJobTest {

    private final StockAlertDigestService service = mock(StockAlertDigestService.class);
    private final AtomicLong nanos = new AtomicLong();

    private StockAlertDigestJob job(Duration budget) {
        return new StockAlertDigestJob(service, new StockAlertDigestProperties("0 0 7 * * *", budget), nanos::get);
    }

    private static BranchSummary branch(long id) {
        return new BranchSummary(id, "CN " + id, "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false);
    }

    @Test
    void notifiesEveryActiveBranchAndContinuesAfterOneFails() {
        when(service.findActiveBranches()).thenReturn(List.of(branch(1), branch(2), branch(3)));
        doThrow(new IllegalStateException("boom")).when(service).notifyBranch(2L, "CN 2");

        job(Duration.ofSeconds(60)).run();

        verify(service).notifyBranch(1L, "CN 1");
        verify(service).notifyBranch(3L, "CN 3");
    }

    @Test
    void stopsWhenTheTimeBudgetIsSpent() {
        when(service.findActiveBranches()).thenReturn(List.of(branch(1), branch(2), branch(3)));
        when(service.notifyBranch(anyLong(), anyString())).thenAnswer(inv -> {
            nanos.addAndGet(Duration.ofMillis(600).toNanos());
            return true;
        });

        job(Duration.ofSeconds(1)).run();

        verify(service).notifyBranch(eq(1L), anyString());
        verify(service).notifyBranch(eq(2L), anyString());
        verify(service, never()).notifyBranch(eq(3L), anyString());
    }

    @Test
    void runLevelFailureIsSwallowed() {
        when(service.findActiveBranches()).thenThrow(new IllegalStateException("db"));

        job(Duration.ofSeconds(1)).run();

        verify(service, never()).notifyBranch(anyLong(), anyString());
    }
}
