package com.petcare.module.iam.service;

import com.petcare.module.iam.entity.User;
import com.petcare.platform.enums.UserRole;

import java.util.UUID;

/**
 * Ranh giới tối giản module IAM (02) gọi từ module Auth (01) — theo
 * docs/convention/backend/01-package-structure.md: "không import trực tiếp
 * entity/repository của module khác". Phục vụ RegisterAccount (tạo hồ sơ
 * Customer đi kèm Account mới), CreateStaff (tạo hồ sơ nhân sự — D-04) và
 * Login/Refresh (tra cứu User theo Account/userId để dựng UserPrincipal).
 */
public interface UserProvisioningService {

    User createCustomerProfile(UUID accountId, String fullName);

    /** CreateStaff (D-04) — tạo hồ sơ User với role/scope binding do Admin chỉ định. */
    User createStaffProfile(UUID accountId, String fullName, UserRole role, UUID organizationId, UUID storeId);

    User findByAccountId(UUID accountId);

    /** RefreshToken (JWT) chỉ mang {@code sub=userId} — dùng cho luồng Refresh. */
    User findById(UUID userId);
}
