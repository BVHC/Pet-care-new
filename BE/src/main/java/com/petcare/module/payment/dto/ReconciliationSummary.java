package com.petcare.module.payment.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Tóm tắt một lần đối soát cổng (payment-v1 #4). */
public record ReconciliationSummary(long reconciliationId, LocalDate reconDate, ReconciliationStatus status,
        int txnCount, int mismatchCount, Instant finishedAt) {

    public enum ReconciliationStatus { RUNNING, MATCHED, MISMATCHED, FAILED }
}
