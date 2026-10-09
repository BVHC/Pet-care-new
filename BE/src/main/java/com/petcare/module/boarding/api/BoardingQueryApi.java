package com.petcare.module.boarding.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Owner: boarding (LT) · BE-2. */
public interface BoardingQueryApi {

    enum StayStatus { BOOKED, CHECKED_IN, OVERDUE, CHECKED_OUT, CANCELLED, NO_SHOW }

    /** {@code emergencyPhone} ghi lúc nhận thú, null trước khi nhận — care gọi theo số này cho PICKUP_OVERDUE (BR-TB-05). */
    record StaySummary(Long bookingId, Long branchId, Long customerId, Long petId, StayStatus status,
                       String emergencyPhone) {}

    /** {@code code} là mã đặt chỗ hiển thị cho lễ tân khi liệt kê đặt chỗ bị ảnh hưởng (branch UC14). */
    record BookedStay(Long bookingId, String code, Long petId, Long customerId, LocalDate checkInDate,
                      LocalDate checkOutDate) {}

    Optional<StaySummary> findBooking(Long bookingId);

    /** Thú đang trong chuồng (CHECKED_IN/OVERDUE) — BR-KH-05, BR-KH-08. */
    boolean isPetInStay(Long petId);

    boolean existsByPet(Long petId);

    boolean existsByCustomer(Long customerId);

    /** Đặt chỗ BOOKED có ngày trả ≥ {@code fromDate}, để branch tính danh sách bị ảnh hưởng (BR-CN-03, 04). */
    List<BookedStay> findBookedFrom(Long branchId, LocalDate fromDate);

    /** Số NO_SHOW + hủy muộn của khách từ {@code since} (BR-LH-09, BR-LT-06). */
    int countViolations(Long customerId, Instant since);

    /** Đặt chỗ BOOKED/CHECKED_IN/OVERDUE của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<StaySummary> findActiveStaysByCustomer(Long customerId);
}
