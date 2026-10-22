package com.petcare.module.boarding.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code boarding_bookings} (erd §7, ROOT, SM #6). Service kiểm {@code BoardingBookingTransitionHandler} và giữ
 * khóa dòng trước khi gọi các method ở đây. Các cột của nhận / trả thú (T42, T43) đã map nhưng chưa có method ghi.
 */
@Getter
@Entity
@Table(name = "boarding_bookings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardingBooking extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private Long petId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "kennel_type_id", nullable = false, updatable = false)
    private Long kennelTypeId;

    @Column(name = "kennel_id")
    private Long kennelId;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(name = "actual_check_in_at")
    private Instant actualCheckInAt;

    @Column(name = "actual_check_out_at")
    private Instant actualCheckOutAt;

    @Column(name = "nightly_price", nullable = false, updatable = false)
    private long nightlyPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BoardingBookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    private BookingEndReason endReason;

    @Column(name = "end_note")
    private String endNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private BookingChannel channel;

    @Column(name = "booked_by", nullable = false, updatable = false)
    private Long bookedBy;

    @Column(name = "late_cancel", nullable = false)
    private boolean lateCancel;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_source")
    private BookingCancelSource cancelSource;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "overdue_since")
    private Instant overdueSince;

    @Column(name = "last_overdue_notice_on")
    private LocalDate lastOverdueNoticeOn;

    @Column(name = "manager_notified_at")
    private Instant managerNotifiedAt;

    /** Đặt chỗ#1: — → BOOKED, giá đêm snapshot. */
    public static BoardingBooking book(String code, Long customerId, Long petId, Long branchId, Long kennelTypeId,
            LocalDate checkInDate, LocalDate checkOutDate, long nightlyPrice, BookingChannel channel, Long bookedBy) {
        BoardingBooking booking = new BoardingBooking();
        booking.code = code;
        booking.customerId = customerId;
        booking.petId = petId;
        booking.branchId = branchId;
        booking.kennelTypeId = kennelTypeId;
        booking.checkInDate = checkInDate;
        booking.checkOutDate = checkOutDate;
        booking.nightlyPrice = nightlyPrice;
        booking.channel = channel;
        booking.bookedBy = bookedBy;
        booking.status = BoardingBookingStatus.BOOKED;
        return booking;
    }

    /** Đặt chỗ#2: gia hạn sửa ngày trả dự kiến, trạng thái giữ nguyên. */
    public void extendTo(LocalDate newCheckOutDate) {
        this.checkOutDate = newCheckOutDate;
    }

    /** Đặt chỗ#4 (khách / lễ tân hủy) và #5 (phòng khám hủy, {@code late = false}). */
    public void cancel(BookingCancelSource source, String reason, boolean late) {
        this.status = BoardingBookingStatus.CANCELLED;
        this.cancelSource = source;
        this.cancelReason = reason;
        this.lateCancel = late;
    }

    /** Đặt chỗ#6 (ST05). */
    public void markNoShow() {
        this.status = BoardingBookingStatus.NO_SHOW;
    }
}
