package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * branch-v1 {@code ScheduleImpact}: lịch hẹn và đặt chỗ lưu trú BOOKED bị ảnh hưởng, hiển thị trước khi quản lý
 * quyết định hủy hàng loạt. {@code customerPhone} để lễ tân gọi khi khách không có email (06 Q4).
 */
public record ScheduleImpact(List<AffectedAppointment> appointments, List<AffectedBoarding> boardingBookings) {

    public record AffectedAppointment(
            Long appointmentId,
            String code,
            LocalDate slotDate,
            @JsonFormat(pattern = "HH:mm") LocalTime slotStart,
            String petName,
            String customerName,
            String customerPhone) {
    }

    public record AffectedBoarding(
            Long bookingId,
            String code,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            String petName,
            String customerName,
            String customerPhone) {
    }

    @JsonIgnore
    public boolean isEmpty() {
        return appointments.isEmpty() && boardingBookings.isEmpty();
    }
}
