package com.petcare.module.iam.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;

/**
 * docs/api/iam-v1.md B — `PATCH /users/me` (RULE-02-06 self-update). Chỉ field
 * thuộc entity {@code User} — KHÔNG cho đổi email/phone (thuộc Account, ngoài
 * phạm vi self-update, xem plan mục H) và KHÔNG cho đổi role/organizationId/
 * storeId (RULE-02-06 "không tự nâng quyền" — A1 iam-v1.md).
 * <p>
 * Field null = giữ nguyên (partial update); khi field được gửi (non-null) thì
 * {@code fullName} không được rỗng/toàn khoảng trắng — tránh xoá trắng tên bắt
 * buộc mà {@code CreateCustomerRequest} đang enforce lúc tạo mới.
 */
public record UpdateOwnProfileRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "fullName không được để trống") String fullName,
        @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
        @Past LocalDate dateOfBirth,
        @Size(max = 255) @URL String avatarUrl
) {
}
