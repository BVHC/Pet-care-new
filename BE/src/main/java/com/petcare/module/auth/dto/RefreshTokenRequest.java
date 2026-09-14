package com.petcare.module.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** ADR-0001 — làm mới access token qua TokenIssuanceFacade.refreshTokens(). */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
