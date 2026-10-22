package com.petcare.module.appointment.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** Owner: appointment (LH) · BE-1. */
public interface AppointmentQueryApi {

    /** {@code code} là mã lịch hẹn hiển thị cho lễ tân khi liệt kê lịch bị ảnh hưởng (branch UC14). */
    record BookedSlot(Long appointmentId, String code, Long branchId, Long petId, Long customerId,
                      LocalDate slotDate, LocalTime slotStart) {}

    /** Lịch BOOKED từ {@code fromDate} của chi nhánh, để branch tính danh sách bị ảnh hưởng (BR-CN-04, BR-LH-10). */
    List<BookedSlot> findBookedFrom(Long branchId, LocalDate fromDate);

    /** BR-KH-06 (xóa thú). */
    boolean existsByPet(Long petId);

    /** BR-TK-19 (hồ sơ online đã phát sinh dữ liệu). */
    boolean existsByCustomer(Long customerId);

    /** Lịch BOOKED của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<BookedSlot> findBookedByCustomer(Long customerId);

    /** Các cột không bao giờ đổi của một lịch hẹn ({@code updatable = false}), mọi trạng thái. */
    record CheckInRef(Long appointmentId, Long branchId, Long petId, Long serviceId) {}

    /**
     * Visit#1 (docs/adr/0059): đọc <b>không khóa</b>, dạng projection (không nạp entity vào persistence context), để
     * visit trả 404, kiểm chi nhánh, thú và dịch vụ của lịch <b>trước</b> {@code AppointmentApi.checkIn} theo thứ tự
     * quyền → tồn tại → 400 → 409 (docs/api/00-method.md L70). An toàn khi không khóa: lịch hẹn không bị xóa cứng và
     * bốn cột trả về không bao giờ đổi; trạng thái không có ở đây — {@code checkIn} kiểm dưới khóa. Không có thì rỗng.
     */
    Optional<CheckInRef> findCheckInRef(Long appointmentId);
}
