package com.petcare.module.boarding.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingCancelSource;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.entity.BookingEndReason;

import io.swagger.v3.oas.annotations.media.Schema;

/** Đặt chỗ lưu trú (boarding-v1 {@code Booking}). */
public record BookingResponse(
        @Schema(description = "Mã đặt chỗ", example = "88")
        Long bookingId,

        @Schema(description = "Mã hiển thị", example = "LT-2610-00012")
        String code,

        @Schema(description = "Khách", example = "14")
        Long customerId,

        @Schema(description = "Tên khách", example = "Nguyễn Văn A")
        String customerName,

        @Schema(description = "Thú cưng", example = "21")
        Long petId,

        @Schema(description = "Tên thú cưng", example = "Milu")
        String petName,

        @Schema(description = "Chi nhánh", example = "1")
        Long branchId,

        @Schema(description = "Loại chuồng đã đặt", example = "5")
        Long kennelTypeId,

        @Schema(description = "Tên loại chuồng", example = "Chuồng chó nhỏ")
        String kennelTypeName,

        @Schema(description = "Chuồng cụ thể, gán lúc nhận thú (BR-LT-08); null trước đó", example = "12")
        Long kennelId,

        @Schema(description = "Mã chuồng cụ thể; null trước khi nhận", example = "C-01")
        String kennelCode,

        @Schema(description = "Ngày nhận dự kiến", example = "2026-11-02")
        LocalDate checkInDate,

        @Schema(description = "Ngày trả dự kiến", example = "2026-11-05")
        LocalDate checkOutDate,

        @Schema(description = "Thời điểm nhận thú thực tế", example = "2026-11-02T09:00:00Z")
        Instant actualCheckInAt,

        @Schema(description = "Thời điểm trả thú thực tế", example = "2026-11-05T10:00:00Z")
        Instant actualCheckOutAt,

        @Schema(description = "Giá một đêm đã snapshot lúc đặt (VND, BR-LT-02)", example = "150000")
        long nightlyPrice,

        @Schema(description = "Trạng thái đặt chỗ", example = "BOOKED")
        BoardingBookingStatus status,

        @Schema(description = "Lý do kết thúc lưu trú; chỉ có khi CHECKED_OUT", example = "RETURNED")
        BookingEndReason endReason,

        @Schema(description = "Kênh đặt", example = "ONLINE")
        BookingChannel channel,

        @Schema(description = "Hủy muộn (BR-LT-06)", example = "false")
        boolean lateCancel,

        @Schema(description = "Ai hủy; chỉ có khi CANCELLED", example = "CUSTOMER")
        BookingCancelSource cancelSource,

        @Schema(description = "Lý do hủy", example = "Đổi lịch công tác")
        String cancelReason,

        @Schema(description = "Bắt đầu quá hạn đón (ST15); null nếu chưa", example = "2026-11-05T05:00:00Z")
        Instant overdueSince,

        @Schema(description = "Order lưu trú đang mở; null cho tới khi bắt đầu trả thú", example = "301")
        Long activeOrderId,

        @Schema(description = "Thú đang lưu trú mà hôm nay chưa có nhật ký (BR-LT-11)", example = "false")
        boolean missingCareLogToday) {
}
