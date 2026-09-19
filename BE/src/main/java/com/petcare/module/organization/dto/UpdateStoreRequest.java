package com.petcare.module.organization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * docs/api/openapi/org-store-v1.yaml #UpdateStoreRequest — `PATCH /stores/{id}`. Đúng 3 field
 * (không có `code`/`organizationId`/`facilityType`/`status` — bất biến sau khi tạo, xem Decision
 * Log UC UpdateStore). Cùng 1 shape cho cả `ORGANIZATION_ADMIN` (mọi Store trong Org mình) và
 * `STORE_MANAGER` (chỉ Store mình quản lý) — khác biệt nằm ở phạm vi Store được sửa (RULE-02-05,
 * {@link com.petcare.platform.security.RoleScopeGuard#assertCanManageStore}), không phải field.
 * Field null = giữ nguyên (partial update); khi field được gửi (non-null) thì `name`/`address`
 * không được rỗng/toàn khoảng trắng.
 */
public record UpdateStoreRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "name không được để trống") String name,
        @Pattern(regexp = ".*\\S.*", message = "address không được để trống") String address,
        @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại phải gồm đúng 10 chữ số") String phone
) {
}
