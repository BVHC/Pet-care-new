package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả gán lượt (visit-v1 schema {@code AssignResult}, #5; docs/adr/0061). Chỉ dựng bởi
 * {@code VisitMapper.toAssignResult}. {@code assigneeOffline} là cảnh báo không chặn của BR-TN-06, cùng giá trị ghi vào
 * {@code visit_assignments.assignee_was_offline}.
 */
public record AssignResult(
        @Schema(description = "Lượt sau khi gán")
        VisitResponse visit,

        @Schema(description = "true = đã cảnh báo người được gán đang offline, không chặn (BR-TN-06)", example = "false")
        boolean assigneeOffline) {
}
