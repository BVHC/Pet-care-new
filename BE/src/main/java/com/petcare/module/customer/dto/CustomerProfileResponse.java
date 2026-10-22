package com.petcare.module.customer.dto;

import com.petcare.module.customer.entity.CustomerChannel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Hồ sơ khách của tôi (customer-v1 {@code CustomerProfile}). {@code email} là email của tài khoản đang đăng nhập — hồ sơ
 * có tài khoản thì email theo tài khoản (BR-KH-01, customer-v1 A5, docs/adr/0028). {@code hasAccount} tính từ
 * {@code customers.account_id}; {@code linkDecisionPending} đọc thẳng {@code customers.link_decision_pending}.
 */
public record CustomerProfileResponse(
        @Schema(description = "Mã hồ sơ khách", example = "315")
        Long customerId,

        @Schema(description = "Họ tên", example = "Nguyễn Thị Lan")
        String fullName,

        @Schema(description = "SĐT; null nếu hồ sơ online chưa khai", example = "0901234567")
        String phone,

        @Schema(description = "Email của tài khoản đang đăng nhập", example = "lan.nguyen@gmail.com")
        String email,

        @Schema(description = "Ảnh đại diện (https); null nếu chưa có",
                example = "https://cdn.petcare.vn/avatars/c315.jpg")
        String avatarUrl,

        @Schema(description = "Nguồn hồ sơ", example = "ONLINE")
        CustomerChannel createdChannel,

        @Schema(description = "Hồ sơ đã gắn tài khoản", example = "true")
        boolean hasAccount,

        @Schema(description = "Còn cờ chờ quyết định liên kết (BR-TK-19)", example = "false")
        boolean linkDecisionPending) {
}
