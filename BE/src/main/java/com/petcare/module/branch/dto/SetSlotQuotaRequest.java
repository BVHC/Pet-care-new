package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.petcare.module.catalog.api.ServiceGroup;

import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code PUT /api/branches/{id}/slot-quotas} (branch-v1 {@code SetSlotQuotaRequest}, UC42). Nhóm dịch vụ,
 * khung giờ và khoảng giá trị của quota kiểm ở service để trả {@code BR-LH-02} / {@code BR-LH-03}.
 */
public record SetSlotQuotaRequest(
        @NotNull(message = "Thiếu nhóm dịch vụ")
        ServiceGroup serviceGroup,

        @NotNull(message = "Thiếu ngày của khung giờ")
        LocalDate slotDate,

        @NotNull(message = "Thiếu giờ bắt đầu của khung")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @NotNull(message = "Thiếu quota")
        Integer quota) {
}
