package com.petcare.module.appointment.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một khung giờ của chi nhánh × dịch vụ × ngày (appointment-v1 schema {@code Slot}, UC39). Chỉ dựng bởi
 * {@code SlotMapper}. Giờ theo định dạng {@code HH:mm} của hợp đồng (00-method §3.6), giờ Việt Nam.
 */
public record SlotResponse(
        @Schema(description = "Giờ bắt đầu khung", type = "string", pattern = "^[0-2][0-9]:[0-5][0-9]$",
                example = "08:30")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "slotStart + 30 phút", type = "string", pattern = "^[0-2][0-9]:[0-5][0-9]$",
                example = "09:00")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotEnd,

        @Schema(description = "Quota hiệu lực của khung: quota riêng → quota mặc định của nhóm → 1 (BR-LH-03)",
                example = "3")
        int quota,

        @Schema(description = "Lịch BOOKED, CHECKED_IN, COMPLETED của khung (BR-LH-03)", example = "1")
        int booked,

        @Schema(description = "FULL hiển thị 'Hết chỗ'; quota 0 cũng là FULL", example = "AVAILABLE")
        SlotStatus status) {

    public enum SlotStatus { AVAILABLE, FULL }
}
