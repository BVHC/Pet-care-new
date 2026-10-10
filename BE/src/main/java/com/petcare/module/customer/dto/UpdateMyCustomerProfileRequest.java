package com.petcare.module.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/me/customer-profile} (customer-v1 {@code UpdateMyCustomerProfileRequest}, UC06). Mọi trường
 * {@code null} = giữ nguyên; chuỗi rỗng (sau {@code strip}) = xóa với {@code phone}, {@code avatarUrl} (docs/adr/0028).
 * Chỉ kiểm hình thức; BR-TK-15 và BR-KH-01 kiểm ở service (convention 06).
 * <ul>
 *   <li>{@code phone}: 10 số {@code 0xxxxxxxxx} (kiểu {@code mobile}, như SĐT nhân viên — 06 §7 G6); chuỗi toàn khoảng
 *       trắng được qua để service hiểu là xóa (hồ sơ tại quầy không xóa được — BR-KH-01).</li>
 *   <li>{@code avatarUrl}: chỉ {@code https://} như ảnh nhân viên (docs/adr/0026).</li>
 *   <li>{@code email}: không có trong schema; chỉ để nhận diện request cố sửa email và từ chối (BR-TK-15).</li>
 * </ul>
 */
public record UpdateMyCustomerProfileRequest(
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Họ tên không được để trống")
        String fullName,

        @Pattern(regexp = "^(\\s*|0\\d{9})$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone,

        @Size(max = 500, message = "Đường dẫn ảnh đại diện tối đa 500 ký tự")
        @Pattern(regexp = "^(\\s*|\\s*https://\\S+\\s*)$", message = "Ảnh đại diện phải là đường dẫn https://")
        String avatarUrl,

        String email) {
}
