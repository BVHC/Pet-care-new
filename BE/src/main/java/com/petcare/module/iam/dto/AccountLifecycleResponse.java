package com.petcare.module.iam.dto;

import com.petcare.platform.enums.AccountStatus;

import java.util.UUID;

/** docs/api/iam-v1.md C4 — response chung cho lock/unlock/deactivate/reactivate. */
public record AccountLifecycleResponse(UUID userId, UUID accountId, AccountStatus status) {
}
