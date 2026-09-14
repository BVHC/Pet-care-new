package com.petcare.module.auth.dto;

/** docs/api/auth-v1.md #LogoutResponse — luôn {@code true} (idempotent, RULE-01-06). */
public record LogoutResponse(boolean revoked) {
}
