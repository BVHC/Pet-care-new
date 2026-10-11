package com.petcare.module.customer.dto;

import com.petcare.module.customer.entity.CustomerChannel;

/**
 * Hồ sơ khách của tôi (customer-v1 {@code CustomerProfile}). {@code email} là email của tài khoản đang đăng nhập — hồ sơ
 * có tài khoản thì email theo tài khoản (BR-KH-01, customer-v1 A5, docs/adr/0028). {@code hasAccount} tính từ
 * {@code customers.account_id}; {@code linkDecisionPending} đọc thẳng {@code customers.link_decision_pending}.
 */
public record CustomerProfileResponse(
        Long customerId,
        String fullName,
        String phone,
        String email,
        String avatarUrl,
        CustomerChannel createdChannel,
        boolean hasAccount,
        boolean linkDecisionPending) {
}
