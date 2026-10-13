package com.petcare.module.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body của {@code POST /api/staff/{id}/change-role} (identity-v1 {@code ChangeRoleRequest}, UC08 — docs/adr/0031). Chỉ
 * kiểm hình thức (convention 06); phân cấp BR-QT-05 và BR-QT-04 kiểm ở service. {@code role} kiểu chuỗi như
 * {@code CreateStaffRequest}: mọi giá trị ngoài 4 chức vụ A05–A08 ({@code ADMIN}, {@code SUPER_MANAGER},
 * {@code CUSTOMER}, chuỗi lạ) cùng trả 400 {@code VALIDATION_FAILED} với tên trường {@code role}. Trường lạ trong JSON
 * bị bỏ qua (gửi kèm {@code branchId} không điều chuyển).
 */
public record ChangeRoleRequest(
        @NotBlank(message = "Chức vụ không được để trống")
        @Pattern(regexp = "^(BRANCH_MANAGER|RECEPTIONIST|VET|CARETAKER)$", message = "Chức vụ không hợp lệ")
        String role) {
}
