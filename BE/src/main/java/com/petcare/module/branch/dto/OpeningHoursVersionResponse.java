package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.util.List;

/** branch-v1 {@code OpeningHoursVersion}: giờ mở cửa cả tuần áp dụng từ {@code effectiveFrom}. */
public record OpeningHoursVersionResponse(LocalDate effectiveFrom, List<DayHoursDto> days) {
}
