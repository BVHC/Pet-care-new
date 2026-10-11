package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/staff} (identity-v1 {@code CreateStaffRequest}, UC08 — docs/adr/0030). Chỉ kiểm hình thức
 * (convention 06); phân cấp BR-QT-01, chi nhánh BR-QT-03, email trùng BR-TK-01 kiểm ở service.
 * <ul>
 *   <li>{@code phone}: 10 số {@code 0xxxxxxxxx} như {@code UpdateStaffProfileRequest}; nhân viên bắt buộc có SĐT
 *       (BR-TK-01).</li>
 *   <li>{@code role}: kiểu chuỗi để mọi giá trị ngoài 5 chức vụ của contract ({@code ADMIN}, {@code CUSTOMER}, chuỗi
 *       lạ) cùng trả 400 {@code VALIDATION_FAILED} với tên trường {@code role}; service đổi sang {@code Role}.</li>
 *   <li>{@code branchId}: bắt buộc với A05–A08, cấm với SUPER_MANAGER (BR-QT-03, kiểm ở service); BRANCH_MANAGER để
 *       trống = chi nhánh của mình (docs/api/00-method.md §3.3).</li>
 * </ul>
 * Không có trường mật khẩu / trạng thái: hệ thống tự sinh mật khẩu (BR-QT-02); trường lạ trong JSON bị bỏ qua.
 */
public record CreateStaffRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone,

        @NotBlank(message = "Họ tên không được để trống")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        String fullName,

        @NotBlank(message = "Chức vụ không được để trống")
        @Pattern(regexp = "^(SUPER_MANAGER|BRANCH_MANAGER|RECEPTIONIST|VET|CARETAKER)$",
                message = "Chức vụ không hợp lệ")
        String role,

        @Positive(message = "branchId phải là số dương")
        Long branchId) {
}
