package com.petcare.module.branch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * branch-v1 {@code PublicBranch} ({@code GET /api/public/branches[/{branchId}]}, UC15). Chỉ chi nhánh ACTIVE; không có
 * trạng thái, thời điểm kích hoạt hay dữ liệu nội bộ (BR-CK-01). {@code weeklyHours} là bản giờ đang áp dụng hôm nay.
 */
public record PublicBranchResponse(
        Long branchId,
        String name,
        String address,
        String phone,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean acceptsAfterHoursEmergency,
        List<DayHoursDto> weeklyHours) {
}
