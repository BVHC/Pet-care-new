package com.petcare.module.identity.api;

/**
 * Publisher: identity (Tài khoản#7, BR-QT-11) · BE-1. Phát đồng bộ trong transaction khóa.
 * Listener: visit (đánh dấu Visit IN_PROGRESS của người bị khóa là cần gán lại, BR-TN-08).
 * {@code branchId} null với ADMIN, SUPER_MANAGER, CUSTOMER.
 */
public record AccountLockedEvent(Long accountId, Role role, Long branchId, String reason, Long actorId) {}
