package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import com.petcare.module.boarding.entity.BoardingBookingStatus;

/**
 * Bộ lọc danh sách đặt chỗ (boarding-v1 #9); trường null = không lọc. {@code from}, {@code to}: đặt chỗ có khoảng
 * {@code [ngày nhận, ngày trả]} giao với {@code [from, to]}. {@code missingCareLogToday} lọc sau khi ghép (cần nhật ký,
 * làm ở service).
 */
public record BookingFilter(BoardingBookingStatus status, Long petId, Long customerId, LocalDate from, LocalDate to,
        Boolean inStay, Boolean missingCareLogToday) {
}
