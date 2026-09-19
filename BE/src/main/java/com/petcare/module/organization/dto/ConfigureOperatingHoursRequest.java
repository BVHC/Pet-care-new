package com.petcare.module.organization.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * docs/api/openapi/org-store-v1.yaml #OperatingHoursRequest — `PUT /stores/{id}/operating-hours`
 * (`ConfigureOperatingHour`, RULE-03-02/07). Semantics replace-all: mảng gửi lên thay thế TOÀN
 * BỘ cấu hình cũ của Store — ngày nào không có trong mảng thì không còn hàng nào sau khi gọi
 * (coi như chưa cấu hình), không phải partial-update như PATCH. Không trùng `dayOfWeek` trong
 * cùng 1 request (validate ở Service).
 */
public record ConfigureOperatingHoursRequest(
        @NotEmpty @Size(max = 7) @Valid List<OperatingHourItem> hours
) {
}
