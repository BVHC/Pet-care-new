package com.petcare.module.appointment.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * Thú đang có lịch nhóm MEDICAL (Khám/Tiêm) {@code BOOKED}: ST04 hoãn nhắc tái khám (BR-TB-06, docs/adr/0088). Đọc
     * không khóa, một câu theo {@code ix_appointments_pet_status}.
     */
    boolean hasBookedMedical(Long petId);

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

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55b, docs/06 §6b, docs/adr/0113): findUpcomingBookedOfCustomer đã có bản thật ở AI-0 (9/11/2026);
    // countBookedByBranch còn là placeholder dạng default — ném UnsupportedOperationException tới khi AppointmentLookupService
    // ghi đè.
    // ------------------------------------------------------------------------------------------------------------

    /** Lịch {@code BOOKED} sắp tới của khách (docs/07 I-10). Không thêm trường vào {@link BookedSlot} (07 L537). */
    record UpcomingBooked(Long appointmentId, String code, Long petId, Long serviceId, Long branchId,
                          LocalDate slotDate, LocalTime slotStart) {}

    /**
     * I-10 (docs/07 §11, UC91, BR-AI-27): lịch {@code BOOKED} của khách có {@code (slot_date, slot_start) >= now}, sắp theo
     * {@code slot_date, slot_start} tăng dần, tối đa {@code limit}. {@code now} là giờ Việt Nam
     * ({@code LocalDateTime.now(clock)}, cùng múi với cột {@code slot_date} / {@code slot_start}). Đọc không khóa. Bản thật
     * ở AI-0 (9/11/2026); {@code limit <= 0} trả rỗng.
     */
    List<UpcomingBooked> findUpcomingBookedOfCustomer(Long customerId, LocalDateTime now, int limit);

    /**
     * BR-CN-07, BR-CN-08 (docs/08 §F.1): số lịch {@code BOOKED} của chi nhánh (mọi ngày). Đọc không khóa. Bản thật trước
     * 7/12 (T75).
     */
    default long countBookedByBranch(Long branchId) {
        throw new UnsupportedOperationException("AppointmentQueryApi.countBookedByBranch chưa cài "
                + "(placeholder T55b, docs/06 §6b); bản thật ở T75");
    }
}
