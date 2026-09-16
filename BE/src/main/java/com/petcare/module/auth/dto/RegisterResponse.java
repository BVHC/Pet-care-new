package com.petcare.module.auth.dto;

import com.petcare.platform.enums.AccountStatus;

import java.util.UUID;

public record RegisterResponse(UUID accountId, AccountStatus status) {
}
