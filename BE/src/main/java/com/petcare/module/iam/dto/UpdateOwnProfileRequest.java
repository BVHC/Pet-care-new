package com.petcare.module.iam.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * docs/api/iam-v1.md B — `PATCH /users/me` (RULE-02-06 self-update). Chỉ field
 * thuộc entity {@code User} — KHÔNG cho đổi email/phone (thuộc Account, ngoài
 * phạm vi self-update, xem plan mục H) và KHÔNG cho đổi role/organizationId/
 * storeId (RULE-02-06 "không tự nâng quyền" — A1 iam-v1.md).
 */
public record UpdateOwnProfileRequest(
        @Size(max = 100) String fullName,
        @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
        @Past LocalDate dateOfBirth,
        @Size(max = 255) String avatarUrl
) {
}
