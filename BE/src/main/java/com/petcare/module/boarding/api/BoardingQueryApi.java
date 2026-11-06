package com.petcare.module.boarding.api;

import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VisitQueryApi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * Mã mọi đặt chỗ của thú, theo id tăng dần (thêm 2026-10-16, T17): sales dùng để biết thú có Order {@code PENDING}
     * nguồn BOARDING hay không (BR-KH-08, {@code OrderQueryApi.hasPendingOrderForPet}).
     */
    List<Long> findBookingIdsByPet(Long petId);

    boolean existsByCustomer(Long customerId);

    /** Đặt chỗ BOOKED có ngày trả ≥ {@code fromDate}, để branch tính danh sách bị ảnh hưởng (BR-CN-03, 04). */
    List<BookedStay> findBookedFrom(Long branchId, LocalDate fromDate);

    /**
     * Số NO_SHOW + hủy muộn của khách xảy ra <b>sau</b> {@code since} (loại trừ: thời điểm vi phạm {@code > since};
     * BR-LH-09, BR-LT-06). Gọi từ {@code BookingRestrictionApi.evaluateAfterViolation} dưới khóa theo khách, nên đọc
     * READ COMMITTED, không khóa thêm (docs/adr/0049).
     */
    int countViolations(Long customerId, Instant since);

    /** Đặt chỗ BOOKED/CHECKED_IN/OVERDUE của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<StaySummary> findActiveStaysByCustomer(Long customerId);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 13, 16): placeholder dạng default — ném UnsupportedOperationException
    // tới khi BoardingQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /** Một lần lưu trú của thú (07 I-7); {@code kennelTypeId} = {@code services.id} của loại chuồng. */
    record StayOfPet(Long bookingId, Long kennelTypeId, StayStatus status, LocalDate checkInDate, LocalDate checkOutDate) {}

    /** I-7 (07 §11, UC92): mọi đặt chỗ lưu trú của thú, mới nhất trước. Bản thật trước 7/12 (T78). */
    default List<StayOfPet> findStaysOfPet(Long petId) {
        throw placeholder("findStaysOfPet", "T78");
    }

    /**
     * I-7 (07 §11, UC92): {@code kennelTypeId → số lượt lưu trú} đã {@code CHECKED_OUT} từ {@code since} của các thú thuộc
     * {@code species} và {@code ageGroup}, toàn chuỗi; loại chuồng không có lượt thì vắng mặt. Bản thật trước 7/12 (T78).
     */
    default Map<Long, Long> countStaysByKennelType(Species species, VisitQueryApi.AgeGroup ageGroup, LocalDate since) {
        throw placeholder("countStaysByKennelType", "T78");
    }

    /** BR-CN-07, BR-CN-08 (UC13): số đặt chỗ {@code CHECKED_IN} / {@code OVERDUE} (thú đang trong chuồng) của chi nhánh. Bản thật trước 7/12 (T75). */
    default long countActiveStaysByBranch(Long branchId) {
        throw placeholder("countActiveStaysByBranch", "T75");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("BoardingQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
