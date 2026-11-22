package com.petcare.module.workforce.dto;

import com.petcare.module.identity.api.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Thống kê số ca của một nhân viên trong tuần (workforce-v1 {@code ShiftWeeklySummary}, #16; BR-NS-12). Đúng 6 trường
 * theo thứ tự hợp đồng; chỉ để quản lý theo dõi, không chặn gì.
 */
public record ShiftWeeklySummaryResponse(
        @Schema(description = "Id tài khoản của nhân viên", example = "12")
        Long staffId,

        @Schema(description = "Họ tên hiện tại của nhân viên", example = "Nguyễn Văn An")
        String staffName,

        @Schema(description = "Chức vụ của nhân viên (chỉ A06–A08 có lịch làm việc)",
                allowableValues = {"RECEPTIONIST", "VET", "CARETAKER"}, example = "VET")
        Role role,

        @Schema(description = "Số phân ca SCHEDULED của nhân viên trong tuần: cả SELF và MANAGER, ở mọi chi nhánh; "
                + "ON_LEAVE, ABSENT, CANCELLED không đếm", example = "8")
        int shiftCount,

        @Schema(description = "Định mức ca mỗi tuần, [CFG] workforce.weekly_required_shifts hiện hành (BR-NS-12)",
                example = "10")
        int requiredShifts,

        @Schema(description = "shiftCount >= requiredShifts; chỉ để theo dõi, hệ thống không chặn gì", example = "false")
        boolean meetsRequirement) {
}
