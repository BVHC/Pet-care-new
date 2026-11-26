package com.petcare.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** {@link BusinessMetrics}: chỉ đếm sau commit, rollback không đếm, ngoài transaction là lỗi (docs/adr/0155). */
class BusinessMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);
    private final TransactionTemplate tx = new TransactionTemplate(new NoResourceTransactionManager());

    @Test
    void countsOnlyAfterCommit() {
        tx.executeWithoutResult(status -> {
            metrics.increment(BusinessMetrics.VISIT_COMPLETED, "branch", "7");
            metrics.add(BusinessMetrics.ORDER_PAID_AMOUNT, 150_000, "branch", "7", "source", "VISIT");
            assertThat(registry.find(BusinessMetrics.VISIT_COMPLETED).counter()).isNull();
        });

        assertThat(registry.get(BusinessMetrics.VISIT_COMPLETED).tag("branch", "7").counter().count()).isEqualTo(1);
        assertThat(registry.get(BusinessMetrics.ORDER_PAID_AMOUNT).tags("branch", "7", "source", "VISIT")
                .counter().count()).isEqualTo(150_000);
    }

    @Test
    void rollbackCountsNothing() {
        tx.executeWithoutResult(status -> {
            metrics.increment(BusinessMetrics.APPOINTMENT_BOOKED, "branch", "1", "channel", "ONLINE");
            status.setRollbackOnly();
        });

        assertThat(registry.find(BusinessMetrics.APPOINTMENT_BOOKED).counter()).isNull();
    }

    @Test
    void outsideTransactionIsAProgrammingError() {
        assertThatThrownBy(() -> metrics.increment(BusinessMetrics.VISIT_CHECKED_IN, "branch", "1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void aBrokenTagListAfterCommitDoesNotFailTheCommittedCall() {
        tx.executeWithoutResult(status -> metrics.increment(BusinessMetrics.VISIT_COMPLETED, "branch"));

        assertThat(registry.find(BusinessMetrics.VISIT_COMPLETED).counter()).isNull();
    }

    /** Transaction manager không có tài nguyên: đủ để chạy đồng bộ hóa commit / rollback thật của Spring. */
    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
