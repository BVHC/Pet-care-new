package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.petcare.module.catalog.api.ServiceGroup;

/** branch-v1 {@code SlotQuota}: quota riêng của một khung giờ; 0 là khóa khung (BR-LH-03). */
public record SlotQuotaResponse(
        Long slotQuotaId,
        ServiceGroup serviceGroup,
        LocalDate slotDate,
        @JsonFormat(pattern = "HH:mm") LocalTime slotStart,
        int quota) {
}
