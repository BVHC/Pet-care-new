package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;

/**
 * {@code CashierShiftQueryApi} thật (T30, trả nợ D016; docs/adr/0064): ca {@code OPEN} của một lễ tân, đọc thuần,
 * không khóa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class CashierShiftLookupIT extends CashierItSupport {

    @Autowired
    private CashierShiftQueryApi shifts;

    private final Instant opened = Instant.parse("2005-06-15T01:00:00Z");
    private final LocalDate day = LocalDate.parse("2005-06-15");

    private Optional<OpenShift> find(long cashier) {
        return new TransactionTemplate(transactionManager).execute(status -> shifts.findOpenShift(cashier));
    }

    @Test
    void openShiftIsReturnedWithItsBranch() {
        long branch = newBranch();
        long cashier = receptionist(branch);
        long shift = openShiftSql(branch, cashier, false, day, opened);

        assertThat(find(cashier)).contains(new OpenShift(shift, branch));
    }

    @Test
    void closedOrReconciledShiftIsNotOpen() {
        long branch = newBranch();
        long cashier = receptionist(branch);
        long shift = openShiftSql(branch, cashier, false, day, opened);
        jdbc.update("UPDATE cashier_shifts SET status = 'CLOSED', counted_cash = 0, closed_at = now() WHERE id = ?",
                shift);
        assertThat(find(cashier)).isEmpty();

        jdbc.update("UPDATE cashier_shifts SET status = 'RECONCILED' WHERE id = ?", shift);
        assertThat(find(cashier)).isEmpty();
    }

    @Test
    void cashierWithoutShiftIsEmpty() {
        assertThat(find(receptionist(newBranch()))).isEmpty();
    }

    @Test
    void readsWithoutWaitingForARowLock() throws Exception {
        long branch = newBranch();
        long cashier = receptionist(branch);
        long shift = openShiftSql(branch, cashier, false, day, opened);
        CompletableFuture<Void> holding = new CompletableFuture<>();
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM cashier_shifts WHERE id = ? FOR UPDATE", Long.class, shift);
                    locked.complete(null);
                    holding.join();
                }));
        try {
            locked.get(5, TimeUnit.SECONDS);
            Optional<OpenShift> found = CompletableFuture.supplyAsync(() -> find(cashier)).get(2, TimeUnit.SECONDS);
            assertThat(found).contains(new OpenShift(shift, branch));
        } finally {
            holding.complete(null);
            holder.get(5, TimeUnit.SECONDS);
        }
    }
}
