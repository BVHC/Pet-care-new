package com.petcare.module.iam.dto;

/** docs/api/iam-v1.md C4 — `POST /users/{id}/deactivate`, reason optional. */
public record DeactivateRequest(String reason) {
}
