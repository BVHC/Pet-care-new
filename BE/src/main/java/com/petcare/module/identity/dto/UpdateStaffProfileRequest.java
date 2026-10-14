package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Họ tên; null = giữ nguyên", example = "Lê Minh Châu")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Họ tên không được để trống")
        String fullName,

        @Schema(description = "Ảnh đại diện https; null = giữ nguyên, chuỗi rỗng = xóa",
                example = "https://cdn.petcare.vn/avatars/18.jpg")
        @Size(max = 500, message = "Đường dẫn ảnh đại diện tối đa 500 ký tự")
        @Pattern(regexp = "^(\\s*|\\s*https://\\S+\\s*)$", message = "Ảnh đại diện phải là đường dẫn https://")
        String avatarUrl,

        @Schema(description = "Số điện thoại 10 chữ số; null = giữ nguyên", example = "0912345678")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại phải có dạng 0xxxxxxxxx")
        String phone,

        @Schema(description = "Chuyên môn, chỉ VET gửi được; null = giữ nguyên, chuỗi rỗng = xóa",
                example = "Nội khoa chó mèo")
        @Size(max = 200, message = "Chuyên môn tối đa 200 ký tự")
        String specialty,

        @Schema(description = "Mô tả ngắn, chỉ VET gửi được, tối đa vet.bio_max_length [CFG] (BR-TK-20)",
                example = "10 năm kinh nghiệm điều trị bệnh nội khoa.")
        String bio,

        @Schema(hidden = true)
        String email) {
}
