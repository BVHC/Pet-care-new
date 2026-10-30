package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.BoardingOrderApi;
import com.petcare.platform.config.TimeConfig;

/**
 * ST15 — đặt chỗ lưu trú quá hạn đón (BR-LT-10, Đặt chỗ#7; docs/adr/0086; cơ chế job: docs/adr/0007). Mỗi đặt chỗ một
 * transaction, thứ tự khóa {@code pets FOR SHARE} (nghĩa vụ của {@code CareTaskApi}, ADR-0079) → {@code
 * boarding_bookings … SKIP LOCKED} → kiểm lại → ghi → outbox → {@code CareTaskApi.create} ({@code care_tasks} cuối).
 * Mốc quá hạn là giờ trả quy định của <b>ngày trả dự kiến</b> ({@code overdue_since}), không phải giờ job chạy, nên kết
 * quả không phụ thuộc độ trễ job; job tắt nhiều ngày rồi chạy lại vẫn thu về đúng trong một lượt. Lỗi của một đặt chỗ
 * rollback riêng nó (job bắt và log).
 */
@Service
public class BoardingOverdueService {

    public enum OverdueResult { MARKED, NOT_DUE, PAID_SKIPPED, SKIPPED }

    public enum FollowUpResult { UPDATED, NOTHING, SKIPPED }

    public record DueRow(long id, long petId, LocalDate checkOutDate) {
    }

    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi configs;
    private final BoardingOrderApi boardingOrders;
    private final CareTaskApi careTasks;
    private final BoardingCapacity capacity;
    private final BoardingBookingRepository bookings;
    private final BoardingBookingTransitionHandler transitions;
    private final BoardingOverdueNotices notices;
    private final Clock clock;

    public BoardingOverdueService(PetQueryApi pets, CatalogQueryApi catalog, SystemConfigApi configs,
            BoardingOrderApi boardingOrders, CareTaskApi careTasks, BoardingCapacity capacity,
            BoardingBookingRepository bookings, BoardingBookingTransitionHandler transitions,
            BoardingOverdueNotices notices, Clock clock) {
        this.pets = pets;
        this.catalog = catalog;
        this.configs = configs;
        this.boardingOrders = boardingOrders;
        this.careTasks = careTasks;
        this.capacity = capacity;
        this.bookings = bookings;
        this.transitions = transitions;
        this.notices = notices;
        this.clock = clock;
    }

    /** Lượt 1: lô tiếp theo của đặt chỗ {@code CHECKED_IN} có ngày trả dự kiến ≤ hôm nay (keyset theo ngày trả rồi id). */
    @Transactional(readOnly = true)
    public List<DueRow> findCheckedInDue(LocalDate today, LocalDate afterDate, long afterId, int limit) {
        var rows = afterDate == null ? bookings.findCheckedInDue(today, limit)
                : bookings.findCheckedInDueAfter(today, afterDate, afterId, limit);
        return rows.stream().map(r -> new DueRow(r.getId(), r.getPetId(), r.getCheckOutDate())).toList();
    }

    /** Lượt 2: lô tiếp theo của đặt chỗ {@code OVERDUE} (keyset theo id). */
    @Transactional(readOnly = true)
    public List<DueRow> findOverdue(long afterId, int limit) {
        return bookings.findOverdueAfter(afterId, limit).stream()
                .map(r -> new DueRow(r.getId(), r.getPetId(), null)).toList();
    }

    /** Đặt chỗ#7: {@code CHECKED_IN → OVERDUE} khi qua giờ trả quy định của ngày trả dự kiến. */
    @Transactional
    public OverdueResult markOverdue(long bookingId, long petId) {
        pets.findPetForShare(petId)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới thú không có"));
        if (bookings.lockCheckedInForOverdue(bookingId).isEmpty()) {
            return OverdueResult.SKIPPED;
        }
        BoardingBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " biến mất dưới khóa"));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        Instant since = booking.getCheckOutDate().atTime(configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME))
                .atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        if (now.isBefore(since)) {
            return OverdueResult.NOT_DUE;
        }
        if (boardingOrders.hasPaidOrder(bookingId)) {
            return OverdueResult.PAID_SKIPPED;
        }
        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.OVERDUE);
        booking.markOverdue(since);
        booking.overdueNoticeSent(today);
        BoardingBooking saved = bookings.saveAndFlush(booking);

        notices.customerOverdue(saved, daysOverdue(saved, today));
        int warnDays = configs.getInt(ConfigKey.BOARDING_CAPACITY_WARNING_DAYS);
        List<BoardingBooking> affected = capacity.shortageBookings(saved.getBranchId(), saved.getKennelTypeId(), today,
                today.plusDays(warnDays));
        if (!affected.isEmpty()) {
            notices.capacityWarning(saved, affected, kennelTypeName(saved));
        }
        if (escalate(saved, now, today)) {
            bookings.saveAndFlush(saved);
        }
        return OverdueResult.MARKED;
    }

    /**
     * Theo dõi đặt chỗ {@code OVERDUE}: thông báo khách mỗi ngày từ giờ trả quy định, Care Task sau {@code
     * boarding.overdue_care_task_days}, báo quản lý một lần sau {@code boarding.overdue_manager_alert_days}.
     */
    @Transactional
    public FollowUpResult followUp(long bookingId, long petId) {
        pets.findPetForShare(petId)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới thú không có"));
        if (bookings.lockOverdueForFollowUp(bookingId).isEmpty()) {
            return FollowUpResult.SKIPPED;
        }
        BoardingBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " biến mất dưới khóa"));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);
        LocalTime checkoutTime = configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME);
        boolean changed = false;
        if ((booking.getLastOverdueNoticeOn() == null || booking.getLastOverdueNoticeOn().isBefore(today))
                && !now.atZone(TimeConfig.BUSINESS_ZONE).toLocalTime().isBefore(checkoutTime)) {
            booking.overdueNoticeSent(today);
            notices.customerOverdue(booking, daysOverdue(booking, today));
            changed = true;
        }
        changed |= escalate(booking, now, today);
        if (changed) {
            bookings.saveAndFlush(booking);
        }
        return changed ? FollowUpResult.UPDATED : FollowUpResult.NOTHING;
    }

    /**
     * Care Task gọi điện và báo quản lý khi đủ ngày. {@code CareTaskApi.create} tự chống trùng (mỗi đặt chỗ tối đa một
     * task, trả id cũ dù task đã đóng) nên gọi lại mỗi lượt là một {@code INSERT … ON CONFLICT DO NOTHING}. Trả
     * {@code true} khi đổi cột của đặt chỗ (đã báo quản lý) — bên gọi lưu.
     */
    private boolean escalate(BoardingBooking booking, Instant now, LocalDate today) {
        Instant taskFrom = booking.getOverdueSince().plus(Duration.ofDays(
                configs.getInt(ConfigKey.BOARDING_OVERDUE_CARE_TASK_DAYS)));
        if (!now.isBefore(taskFrom)) {
            careTasks.create(new NewCareTask(CareTaskType.PICKUP_OVERDUE, booking.getId(), booking.getBranchId(),
                    booking.getCustomerId(), booking.getPetId(), taskFrom.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate()));
        }
        Instant managerFrom = booking.getOverdueSince().plus(Duration.ofDays(
                configs.getInt(ConfigKey.BOARDING_OVERDUE_MANAGER_ALERT_DAYS)));
        if (booking.getManagerNotifiedAt() == null && !now.isBefore(managerFrom)) {
            notices.managerOverdue(booking, daysOverdue(booking, today));
            booking.managerNotified(now);
            return true;
        }
        return false;
    }

    /** Số ngày quá hạn đón tính từ ngày trả dự kiến, tối thiểu 1 (hiển thị trong thông báo). */
    private static int daysOverdue(BoardingBooking booking, LocalDate today) {
        return (int) Math.max(1, ChronoUnit.DAYS.between(booking.getCheckOutDate(), today));
    }

    private String kennelTypeName(BoardingBooking booking) {
        return catalog.findKennelType(booking.getKennelTypeId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới loại chuồng không có: " + booking.getKennelTypeId())).name();
    }
}
