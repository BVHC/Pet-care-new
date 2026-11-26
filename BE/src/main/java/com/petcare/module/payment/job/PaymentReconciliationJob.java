package com.petcare.module.payment.job;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.payment.service.PaymentReconciliationService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;
import com.petcare.platform.metrics.JobMetrics;

import lombok.extern.slf4j.Slf4j;

/** ST14: đối soát ngày liền trước theo giờ Việt Nam. */
@Slf4j
@Component
public class PaymentReconciliationJob {
    private final PaymentReconciliationService reconciliations;
    private final Clock clock;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    public PaymentReconciliationJob(PaymentReconciliationService reconciliations, Clock clock) {
        this.reconciliations = reconciliations;
        this.clock = clock;
    }

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    // Một phiên thành công mỗi ngày. Các mốc sau 02:05 chỉ thử lại nếu mốc trước lỗi.
    @Scheduled(cron = "${app.jobs.payment-reconciliation.cron:0 5,20,35,50 2 * * *}",
            zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        LocalDate date = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).minusDays(1);
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        try {
            if (reconciliations.reconcile(date)) {
                log.info("PAYMENT_RECONCILIATION_COMPLETE date={}", date);
            }
            jobMetrics.record("payment_reconciliation", JobMetrics.Outcome.OK);
        } catch (RuntimeException e) {
            log.error("PAYMENT_RECONCILIATION_FAILED date={} exception={}", date, e.getClass().getSimpleName());
            jobMetrics.record("payment_reconciliation", JobMetrics.Outcome.FAILED);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
