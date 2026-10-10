package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/me/staff-profile} (identity-v1 {@code UpdateStaffProfileRequest}, UC06). Mọi trường
 * {@code null} = giữ nguyên; chuỗi rỗng (sau {@code strip}) = xóa với {@code avatarUrl}, {@code specialty}, {@code bio}
 * (docs/adr/0026). Chỉ kiểm hình thức; BR-TK-15, 20 và phạm vi "chỉ VET" kiểm ở service (convention 06).
 * <ul>
 *   <li>{@code phone}: 10 số {@code 0xxxxxxxxx} theo erd {@code accounts.phone}; chuỗi rỗng cũng bị chặn vì nhân viên
 *       bắt buộc có SĐT (BR-TK-01).</li>
 *   <li>{@code avatarUrl}: chỉ {@code https://}, vì ảnh hiện ở trang công khai (UC15); chuỗi toàn khoảng trắng được qua
 *       để service hiểu là xóa.</li>
 *   <li>{@code bio}: không có {@code @Size} — giới hạn là [CFG] {@code vet.bio_max_length}, lỗi trả BR-TK-20.</li>
 *   <li>{@code email}: không có trong schema; chỉ để nhận diện request cố sửa email và từ chối (BR-TK-15).</li>
 * </ul>
 */
public record UpdateStaffProfileRequest(
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Họ tên không được để trống")
        String fullName,

        @Size(max = 500, message = "Đường dẫn ảnh đại diện tối đa 500 ký tự")
        @Pattern(regexp = "^(\\s*|\\s*https://\\S+\\s*)$", message = "Ảnh đại diện phải là đường dẫn https://")
        String avatarUrl,

        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone,

        @Size(max = 200, message = "Chuyên môn tối đa 200 ký tự")
        String specialty,

        String bio,

        String email) {
}
