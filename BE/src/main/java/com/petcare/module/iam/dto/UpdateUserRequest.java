package com.petcare.module.iam.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;
import java.util.UUID;

/**
 * docs/api/iam-v1.md A1/A6 — `PATCH /users/{id}`. Hai nhóm field loại trừ
 * nhau theo role của target (RULE-02-02/06, xem UserManagementServiceImpl):
 * - Target STAFF: chỉ {@code organizationId}/{@code storeId} (rebind, KHÔNG đổi role).
 *   {@code organizationId} không thể "xoá" (mọi role staff đều bắt buộc org —
 *   RULE-02-02, updateUser không đổi role nên không cần); {@code storeId} có
 *   thể xoá qua {@code clearStoreId=true} (vd FINANCE_STAFF chỉ bắt buộc org,
 *   store tuỳ chọn) vì {@code storeId=null} vốn đã có nghĩa "giữ nguyên".
 * - Target CUSTOMER: chỉ 4 field profile (Receptionist sửa hồ sơ tại quầy,
 *   RULE-02-06) — Customer không được gắn organizationId/storeId. {@code fullName}
 *   null = giữ nguyên, nhưng khi gửi (non-null) không được rỗng/toàn khoảng
 *   trắng — tránh Receptionist xoá trắng tên bắt buộc của khách hàng.
 */
public record UpdateUserRequest(
        UUID organizationId,
        UUID storeId,
        boolean clearStoreId,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "fullName không được để trống") String fullName,
        @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
        @Past LocalDate dateOfBirth,
        @Size(max = 255) @URL String avatarUrl
) {
}
