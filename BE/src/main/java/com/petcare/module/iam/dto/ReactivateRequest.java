package com.petcare.module.iam.dto;

import jakarta.validation.constraints.NotBlank;

/** docs/api/iam-v1.md C4 — `POST /users/{id}/reactivate`, reason bắt buộc (RULE-02-07). */
public record ReactivateRequest(@NotBlank String reason) {
}
