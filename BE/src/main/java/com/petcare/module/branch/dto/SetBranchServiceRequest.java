package com.petcare.module.branch.dto;

import jakarta.validation.constraints.NotNull;

/** Body của {@code PUT /api/branches/{id}/services/{serviceId}} (branch-v1 {@code SetBranchServiceRequest}, UC33). */
public record SetBranchServiceRequest(
        @NotNull(message = "Thiếu cờ bật / tắt dịch vụ")
        Boolean enabled) {
}
