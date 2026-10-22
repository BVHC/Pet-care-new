package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookedDue;
import com.petcare.module.branch.api.BranchQueryApi;

/**
 * ST05 cho đặt chỗ lưu trú (Đặt chỗ#6, BR-LT-06; docs/adr/0065, khuôn docs/adr/0053): mỗi đặt chỗ {@code BOOKED} chưa
 * được nhận khi hết giờ làm việc của ngày nhận chuyển {@code NO_SHOW} trong transaction riêng, rồi đánh giá hạn chế
 * đặt online (BR-LH-09). Không {@code try/catch}: lỗi ở bất kỳ bước nào rollback cả đặt chỗ đó, job log và thử lại lượt
 * sau. Mốc hết giờ đọc lúc chạy (giờ mở cửa hiện hành, ADR-0057).
 */
@Service
public class BoardingNoShowService {

    /** Kết quả xử lý một đặt chỗ. */
    public enum NoShowResult {
        /** Đã chuyển {@code NO_SHOW} và đã đánh giá BR-LH-09. */
        MARKED,
        /** Không làm gì: đã rời {@code BOOKED}, chưa hết giờ ngày nhận, hoặc đang bị luồng khác khóa. */
        SKIPPED
    }

    /** Khóa keyset của một đặt chỗ tới hạn. */
    public record DueBooking(long id, LocalDate checkInDate) {
    }

    private final BoardingBookingRepository bookings;
    private final BoardingBookingTransitionHandler transitions;
    private final BookingRestrictionApi restrictions;
    private final BranchQueryApi branches;
    private final Clock clock;

    public BoardingNoShowService(BoardingBookingRepository bookings, BoardingBookingTransitionHandler transitions,
            BookingRestrictionApi restrictions, BranchQueryApi branches, Clock clock) {
        this.bookings = bookings;
        this.transitions = transitions;
        this.restrictions = restrictions;
        this.branches = branches;
        this.clock = clock;
    }

    /**
     * Tối đa {@code limit} đặt chỗ {@code BOOKED} có ngày nhận ≤ {@code today}, theo {@code (ngày nhận, id)};
     * {@code afterDate} null = lô đầu, ngược lại tiếp từ {@code (afterDate, afterId)} loại trừ. Không khóa.
     */
    @Transactional(readOnly = true)
    public List<DueBooking> findDue(LocalDate today, LocalDate afterDate, long afterId, int limit) {
        Objects.requireNonNull(today, "today");
        List<BookedDue> rows = afterDate == null ? bookings.findBookedDue(today, limit)
                : bookings.findBookedDueAfter(today, afterDate, afterId, limit);
        return rows.stream().map(r -> new DueBooking(r.getId(), r.getCheckInDate())).toList();
    }

    @Transactional
    public NoShowResult markNoShow(long bookingId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("markNoShow cần transaction ghi, không được readOnly");
        }
        if (bookings.lockBookedForNoShow(bookingId).isEmpty()) {
            return NoShowResult.SKIPPED;
        }
        BoardingBooking booking = bookings.findById(bookingId).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + bookingId + " đã khóa nhưng không đọc được"));
        Instant now = clock.instant();
        Instant cutoff = BoardingRules.noShowCutoff(booking.getCheckInDate(),
                branches.openingRangesOn(booking.getBranchId(), booking.getCheckInDate()));
        if (now.isBefore(cutoff)) {
            return NoShowResult.SKIPPED;
        }
        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.NO_SHOW);
        booking.markNoShow();
        bookings.saveAndFlush(booking);
        // Thời điểm vi phạm = updated_at đã lưu (countViolations đọc đúng cột này, D3).
        restrictions.evaluateAfterViolation(booking.getCustomerId(), bookings.findUpdatedAt(bookingId));
        return NoShowResult.MARKED;
    }
}
