package com.petcare.module.auth.service;

import com.petcare.module.auth.entity.Account;

import java.util.UUID;

/**
 * Kết quả nội bộ (không phải DTO API) trả cho Controller để orchestrate bước
 * dispatch notification NGOÀI transaction của registerAccount()/resendOtp()
 * — xem javadoc NotificationService (lý do tách enqueue/dispatch).
 */
public record RegistrationOutcome(Account account, UUID notificationTaskId) {
}
