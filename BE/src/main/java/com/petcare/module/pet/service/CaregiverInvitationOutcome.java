package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CaregiverInvitationResponse;

import java.util.UUID;

/**
 * Kết quả nội bộ (không phải DTO API) trả cho Controller để orchestrate bước dispatch
 * notification NGOÀI transaction của inviteCaregiver() — cùng lý do với
 * module/auth/service/RegistrationOutcome (xem javadoc NotificationService).
 * notificationTaskId null nghĩa là không gửi email (người được mời chưa có tài khoản).
 */
public record CaregiverInvitationOutcome(
        CaregiverInvitationResponse response,
        UUID notificationTaskId,
        String toAddress
) {}
