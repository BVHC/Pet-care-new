package com.petcare.module.identity.dto;

import com.petcare.module.identity.api.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Nhân viên có thể gán lượt (identity-v1 {@code AssignableStaff}, {@code GET /api/staff/assignable}, UC45; docs/adr/0089).
 * Đúng 4 trường: không có email, SĐT, trạng thái, khóa, chi nhánh, chuyên môn, giới thiệu — danh sách này mở cho lễ tân,
 * còn {@code StaffResponse} của {@code GET /api/staff} thì không. {@code accountId} là giá trị gửi làm
 * {@code assigneeId} của visit-v1 #5, #9.
 */
public record AssignableStaffResponse(
        @Schema(description = "Mã tài khoản", example = "18")
        Long accountId,

        @Schema(description = "Họ tên", example = "BS. Lê Minh Châu")
        String fullName,

        @Schema(description = "Chức vụ: VET hoặc CARETAKER", example = "VET")
        Role role,

        @Schema(description = "Có phiên chưa hủy với access token còn hạn (BR-TN-06, ADR-0091); chỉ là gợi ý",
                example = "true")
        boolean online) {
}
