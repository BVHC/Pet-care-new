package com.petcare.module.payment.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mục lệch của một phiên đối soát (payment-v1 #5, #6). */
public record ReconciliationItem(long itemId,
        @Schema(allowableValues = {"MISSING_LOCAL", "MISSING_GATEWAY", "AMOUNT_DIFF", "STATUS_DIFF"}) String kind,
        Long transactionId, String gatewayRef, Long ourAmount,
        Long gatewayAmount, boolean resolved, String resolvedNote, Instant resolvedAt) {}
