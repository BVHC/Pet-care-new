package com.petcare.module.pet.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CaregiverDelegationResponse(
        UUID id,
        UUID petId,
        UUID caregiverUserId,
        String caregiverEmail,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime validUntil
) {}
