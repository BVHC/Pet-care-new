package com.petcare.module.boarding.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingCancelSource;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;

/**
 * Phòng khám hủy đặt chỗ lưu trú (Đặt chỗ#5, BR-LT-08, BR-LH-10; docs/adr/0065, khuôn
 * {@code AppointmentClinicCancellationService}). {@code MANDATORY}: chạy trong transaction của bên phát sự kiện, khóa
 * dòng theo lô id tăng dần, bỏ qua dòng đã rời {@code BOOKED}, đặt {@code CLINIC}, không tính hủy muộn, rồi ghi outbox.
 * Không bắt lỗi: lỗi ở đây rollback cả bên phát (đánh dấu thú mất, chuyển chủ, thêm ngày nghỉ).
 */
@Service
public class BoardingClinicCancellationService {

    static final int BATCH = 100;

    private final BoardingBookingRepository bookings;
    private final BoardingBookingTransitionHandler transitions;
    private final BoardingClinicCancellationNotices notices;

    public BoardingClinicCancellationService(BoardingBookingRepository bookings,
            BoardingBookingTransitionHandler transitions, BoardingClinicCancellationNotices notices) {
        this.bookings = bookings;
        this.transitions = transitions;
        this.notices = notices;
    }

    /**
     * Hủy các đặt chỗ {@code bookingIds} còn {@code BOOKED} sau khóa; trả id các đặt chỗ đã hủy (tăng dần). Id không
     * tồn tại → {@link IllegalStateException} (đặt chỗ không bị xóa cứng: thiếu là dữ liệu hỏng).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> cancelByClinic(Collection<Long> bookingIds, BoardingClinicCause cause) {
        Objects.requireNonNull(bookingIds, "bookingIds");
        Objects.requireNonNull(cause, "cause");
        requireWritableTransaction();
        List<Long> ids = List.copyOf(new TreeSet<>(bookingIds));
        if (ids.isEmpty()) {
            return List.of();
        }
        List<BoardingBooking> locked = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            List<BoardingBooking> rows = bookings.findAllByIdInForUpdate(batch);
            if (rows.size() != batch.size()) {
                throw new IllegalStateException("Hủy do phòng khám trỏ tới đặt chỗ lưu trú không tồn tại: " + batch);
            }
            locked.addAll(rows);
        }
        List<BoardingBooking> cancelled = new ArrayList<>(locked.size());
        for (BoardingBooking booking : locked) {
            if (booking.getStatus() != BoardingBookingStatus.BOOKED) {
                continue;
            }
            transitions.validateTransition(BoardingBookingStatus.BOOKED, BoardingBookingStatus.CANCELLED);
            booking.cancel(BookingCancelSource.CLINIC, cause.reason(), false);
            cancelled.add(bookings.saveAndFlush(booking));
        }
        notices.enqueueFor(cancelled, cause);
        return cancelled.stream().map(BoardingBooking::getId).toList();
    }

    /** Hủy mọi đặt chỗ {@code BOOKED} của thú (thú mất, chuyển chủ). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> cancelBookedOfPet(Long petId, BoardingClinicCause cause) {
        Objects.requireNonNull(petId, "petId");
        requireWritableTransaction();
        return cancelByClinic(bookings.findBookedIdsByPetId(petId), cause);
    }

    private static void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("cancelByClinic cần transaction ghi, không được readOnly");
        }
    }
}
