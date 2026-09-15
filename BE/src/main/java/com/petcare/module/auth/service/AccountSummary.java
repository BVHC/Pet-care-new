package com.petcare.module.auth.service;

import com.petcare.platform.enums.AccountStatus;

import java.util.UUID;

/**
 * Ranh giới cho module IAM (02) — không lộ entity {@code Account} ra ngoài
 * module Auth (docs/convention/backend/01-package-structure.md).
 */
public record AccountSummary(UUID accountId, AccountStatus status) {
}
