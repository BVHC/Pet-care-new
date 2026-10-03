package com.petcare.module.appointment.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Owner: appointment (LH) · BE-1. */
public interface AppointmentQueryApi {

    record BookedSlot(Long appointmentId, Long branchId, Long petId, Long customerId, LocalDate slotDate,
                      LocalTime slotStart) {}

    /** Lịch BOOKED từ {@code fromDate} của chi nhánh, để branch tính danh sách bị ảnh hưởng (BR-CN-04, BR-LH-10). */
    List<BookedSlot> findBookedFrom(Long branchId, LocalDate fromDate);

    /** BR-KH-06 (xóa thú). */
    boolean existsByPet(Long petId);

    /** BR-TK-19 (hồ sơ online đã phát sinh dữ liệu). */
    boolean existsByCustomer(Long customerId);

    /** Lịch BOOKED của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<BookedSlot> findBookedByCustomer(Long customerId);
}
