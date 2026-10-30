package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.dto.CheckoutStartedResponse;
import com.petcare.module.boarding.dto.DeceasedEndRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingEndReason;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.BoardingOrderApi;
import com.petcare.module.sales.api.BoardingOrderApi.BoardingCharge;
import com.petcare.module.sales.api.BoardingOrderApi.BoardingOrderRef;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Bắt đầu trả thú, giao thú, kết thúc do thú mất (UC59, boarding-v1 #16–18; Đặt chỗ#9, #10, #11; Chuồng#3;
 * docs/adr/0086). Chỉ lễ tân (controller). Khóa {@code boarding_bookings FOR NO KEY UPDATE → orders
 * (BoardingOrderApi) → document_counters}; giao thú và thú mất khóa thêm {@code kennels} sau đặt chỗ. Thứ tự lỗi như
 * ADR-0049: 404 / 403 → 409 trạng thái → 400 rule.
 */
@Service
public class BoardingCheckoutService {

    private static final int DESCRIPTION_MAX = 200;

    private final BranchScope branchScope;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi configs;
    private final BoardingOrderApi boardingOrders;
    private final BoardingBookingRepository bookings;
    private final KennelRepository kennels;
    private final BoardingBookingTransitionHandler transitions;
    private final KennelTransitionHandler kennelTransitions;
    private final BoardingAssembler assembler;
    private final Clock clock;

    public BoardingCheckoutService(BranchScope branchScope, BranchQueryApi branches, CatalogQueryApi catalog,
            SystemConfigApi configs, BoardingOrderApi boardingOrders, BoardingBookingRepository bookings,
            KennelRepository kennels, BoardingBookingTransitionHandler transitions,
            KennelTransitionHandler kennelTransitions, BoardingAssembler assembler, Clock clock) {
        this.branchScope = branchScope;
        this.branches = branches;
        this.catalog = catalog;
        this.configs = configs;
        this.boardingOrders = boardingOrders;
        this.bookings = bookings;
        this.kennels = kennels;
        this.transitions = transitions;
        this.kennelTransitions = kennelTransitions;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** #16 (Đặt chỗ#9): mở hoặc dùng lại Order lưu trú {@code PENDING}, tính lại số đêm. */
    @Transactional
    public CheckoutStartedResponse startCheckout(Long bookingId) {
        Long actor = authorize(bookingId);
        BoardingBooking booking = lock(bookingId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        requireInKennel(booking);
        if (!BoardingRules.withinOpeningHours(now, branches.openingRangesOn(booking.getBranchId(), today))) {
            throw new BusinessRuleViolationException("BR-LT-04", "Chỉ trả thú trong giờ mở cửa của chi nhánh");
        }
        if (boardingOrders.hasPaidOrder(bookingId)) {
            throw new BoardingConflictException("Đã thu tiền lưu trú, chỉ còn giao thú");
        }
        int nights = BoardingRules.chargedNights(booking.getCheckInDate(), today,
                BoardingRules.afterCheckoutTime(now, configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)));
        BoardingOrderRef order = open(booking, nights, actor);
        return new CheckoutStartedResponse(assembler.toResponse(booking, today), order.orderId(), order.nights(),
                order.amount());
    }

    /** #17 (Đặt chỗ#10, Chuồng#3): chỉ giao khi Order lưu trú đã {@code PAID}, không ngoại lệ (BR-LT-09). */
    @Transactional
    public BookingResponse handOver(Long bookingId) {
        authorize(bookingId);
        BoardingBooking booking = lock(bookingId);
        Instant now = clock.instant();

        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.CHECKED_OUT);
        if (!boardingOrders.hasPaidOrder(bookingId)) {
            throw new BusinessRuleViolationException("BR-LT-09", "Order lưu trú chưa thanh toán, chưa giao thú được");
        }
        Kennel kennel = lockKennel(booking);
        booking.checkOut(BookingEndReason.RETURNED, null, now);
        release(kennel);
        BoardingBooking saved = bookings.saveAndFlush(booking);
        kennels.saveAndFlush(kennel);
        return assembler.toResponse(saved, LocalDate.now(clock));
    }

    /** #18 (Đặt chỗ#11, Chuồng#3): thú mất, tính tiền tới hôm nay, điều kiện {@code PAID} không áp dụng (BR-LT-12). */
    @Transactional
    public CheckoutStartedResponse endDeceased(Long bookingId, DeceasedEndRequest request) {
        Long actor = authorize(bookingId);
        BoardingBooking booking = lock(bookingId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.CHECKED_OUT);
        Kennel kennel = lockKennel(booking);
        booking.checkOut(BookingEndReason.PET_DECEASED, request.note().strip(), now);
        release(kennel);
        BoardingBooking saved = bookings.saveAndFlush(booking);
        kennels.saveAndFlush(kennel);
        BoardingOrderRef order = boardingOrders.findPaidOrder(bookingId)
                .orElseGet(() -> open(saved, BoardingRules.chargedNights(saved.getCheckInDate(), today, false), actor));
        return new CheckoutStartedResponse(assembler.toResponse(saved, today), order.orderId(), order.nights(),
                order.amount());
    }

    /** Nhân viên chi nhánh khác → 403; đặt chỗ không có → 404. Trả id người thao tác. */
    private Long authorize(Long bookingId) {
        BookingKeys keys = bookings.findKeys(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        branchScope.check(keys.getBranchId());
        return branchScope.current().accountId();
    }

    private BoardingBooking lock(Long bookingId) {
        return bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
    }

    private Kennel lockKennel(BoardingBooking booking) {
        return kennels.findByIdForUpdate(booking.getKennelId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới chuồng không có: " + booking.getKennelId()));
    }

    private void release(Kennel kennel) {
        kennelTransitions.validateTransition(kennel.getStatus(), KennelStatus.AVAILABLE);
        kennel.release();
    }

    private static void requireInKennel(BoardingBooking booking) {
        if (booking.getStatus() != BoardingBookingStatus.CHECKED_IN
                && booking.getStatus() != BoardingBookingStatus.OVERDUE) {
            throw new BoardingConflictException("Thú không còn trong chuồng");
        }
    }

    private BoardingOrderRef open(BoardingBooking booking, int nights, Long actor) {
        String typeName = catalog.findKennelType(booking.getKennelTypeId()).orElseThrow(() -> new IllegalStateException(
                "Đặt chỗ #" + booking.getId() + " trỏ tới loại chuồng không có: " + booking.getKennelTypeId())).name();
        String description = "Lưu trú " + typeName + " · " + booking.getCode();
        if (description.length() > DESCRIPTION_MAX) {
            description = description.substring(0, DESCRIPTION_MAX);
        }
        return boardingOrders.openOrReuse(new BoardingCharge(booking.getId(), booking.getBranchId(),
                booking.getCustomerId(), booking.getKennelTypeId(), description, nights, booking.getNightlyPrice(),
                actor));
    }
}
