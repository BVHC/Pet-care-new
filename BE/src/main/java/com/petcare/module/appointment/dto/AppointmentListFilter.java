package com.petcare.module.appointment.dto;

import java.time.LocalDate;

import com.petcare.module.appointment.entity.AppointmentStatus;

/**
 * Bộ lọc của danh sách lịch hẹn (appointment-v1 #3, docs/adr/0044 §5). Mọi trường có thể null = không lọc; trường nào
 * được gửi thì AND với nhau và với phạm vi của người xem. {@code checkInReady} chỉ có tác dụng khi là {@code true}.
 */
public record AppointmentListFilter(Long branchId, LocalDate date, LocalDate from, LocalDate to,
        AppointmentStatus status, Long petId, Long customerId, Boolean checkInReady) {

    public boolean checkInReadyRequested() {
        return Boolean.TRUE.equals(checkInReady);
    }
}
