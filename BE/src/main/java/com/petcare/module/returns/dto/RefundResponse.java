package com.petcare.module.returns.dto;

import java.time.Instant;

import com.petcare.module.sales.api.CollectMethod;

public record RefundResponse(long refundId, String code, Long returnRequestId, Long onlineOrderId, CollectMethod method,
                             long amount, String status, int attempts, String failureReason, Instant completedAt,
                             Instant createdAt) {}
