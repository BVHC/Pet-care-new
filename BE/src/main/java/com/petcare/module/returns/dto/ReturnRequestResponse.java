package com.petcare.module.returns.dto;

import java.time.Instant;
import java.util.List;

import com.petcare.module.returns.dto.CreateReturnRequest.Reason;

public record ReturnRequestResponse(long requestId, String code, long orderId, long branchId, String status,
                                    Reason reasonCode, String reasonNote, List<Line> lines, Boolean restock,
                                    Long refundAmount, String decisionNote, Instant createdAt, Instant decidedAt) {

    public record Line(long orderLineId, String productName, long unitPrice, int quantity) {}
}
