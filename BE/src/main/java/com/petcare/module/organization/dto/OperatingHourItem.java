package com.petcare.module.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * docs/api/openapi/org-store-v1.yaml #OperatingHoursRequest/#OperatingHoursResponse — dùng
 * chung cho cả request lẫn response item. `dayOfWeek` 1=Chủ nhật...7=Thứ bảy (CONFIRMED theo
 * ERD, khác ISO). `openTime`/`closeTime` bắt buộc cùng có khi `isClosed=false`, bắt buộc cùng
 * null khi `isClosed=true` — validate ở Service (RULE-03-07), không phải Bean Validation vì là
 * ràng buộc chéo field.
 */
public record OperatingHourItem(
        @NotNull @Min(1) @Max(7) Integer dayOfWeek,
        LocalTime openTime,
        LocalTime closeTime,
        @NotNull Boolean isClosed
) {
}
