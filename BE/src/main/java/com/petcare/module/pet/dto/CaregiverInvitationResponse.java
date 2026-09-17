package com.petcare.module.pet.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * invitationToken chỉ khác null khi người được mời CHƯA có tài khoản (spec D-02) —
 * khi đó raw token được trả đúng một lần cho Primary Owner tự chuyển đi.
 */
public record CaregiverInvitationResponse(
        UUID id,
        UUID petId,
        String caregiverEmail,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime validUntil,
        String invitationToken
) {}
