package com.petcare.module.iam.service;

import com.petcare.module.iam.entity.User;

import java.util.UUID;

/**
 * Ranh giới tối giản module IAM (02) gọi từ module Auth (01) — theo
 * docs/convention/backend/01-package-structure.md: "không import trực tiếp
 * entity/repository của module khác". Phục vụ RegisterAccount (tạo hồ sơ
 * Customer đi kèm Account mới) và Login/Refresh (tra cứu User theo Account/
 * userId để dựng UserPrincipal). Mở rộng khi Module 02 triển khai đầy đủ.
 */
public interface UserProvisioningService {

    User createCustomerProfile(UUID accountId, String fullName);

    User findByAccountId(UUID accountId);

    /** RefreshToken (JWT) chỉ mang {@code sub=userId} — dùng cho luồng Refresh. */
    User findById(UUID userId);
}
