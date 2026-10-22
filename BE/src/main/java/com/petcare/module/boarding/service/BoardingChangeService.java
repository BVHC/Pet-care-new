package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.dto.CancelBookingRequest;
import com.petcare.module.boarding.dto.ExtendBookingRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingCancelSource;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.exception.InvalidBoardingRequestException;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Gia hạn và hủy đặt chỗ (UC58, boarding-v1 #11, #12; Đặt chỗ#2, #4; docs/adr/0065). Gia hạn khóa
 * {@code pets FOR SHARE → branches → boarding_bookings} (sức chứa phải kiểm dưới khóa chi nhánh); hủy chỉ khóa dòng
 * đặt chỗ rồi, nếu muộn, {@code evaluateAfterViolation} (advisory 1002, luôn là khóa cuối). Thứ tự lỗi như ADR-0049:
 * 404 / 403 → 409 trạng thái → 400 rule.
 */
@Service
public class BoardingChangeService {

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final BookingRestrictionApi restrictions;
    private final SystemConfigApi configs;
    private final BoardingCapacity capacity;
    private final BoardingAvailabilityService availability;
    private final BoardingBookingRepository bookings;
    private final BoardingBookingTransitionHandler transitions;
    private final BoardingAssembler assembler;
    private final Clock clock;

    public BoardingChangeService(BranchScope branchScope, CustomerQueryApi customers, PetQueryApi pets,
            BranchApi branchLocks, BranchQueryApi branches, BookingRestrictionApi restrictions,
            SystemConfigApi configs, BoardingCapacity capacity, BoardingAvailabilityService availability,
            BoardingBookingRepository bookings, BoardingBookingTransitionHandler transitions,
            BoardingAssembler assembler, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.pets = pets;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.restrictions = restrictions;
        this.configs = configs;
        this.capacity = capacity;
        this.availability = availability;
        this.bookings = bookings;
        this.transitions = transitions;
        this.assembler = assembler;
        this.clock = clock;
    }

    @Transactional
    public BookingResponse extendBooking(Long bookingId, ExtendBookingRequest request) {
        BookingKeys keys = authorize(bookingId);
        pets.findPetForShare(keys.getPetId())
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới thú không có"));
        branchLocks.lockForBooking(keys.getBranchId())
                .orElseThrow(() -> new IllegalStateException("Đặt chỗ #" + bookingId + " trỏ tới chi nhánh không có"));
        BoardingBooking booking = bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        LocalDate today = LocalDate.now(clock);
        LocalDate newOut = request.checkOutDate();

        if (booking.getStatus() != BoardingBookingStatus.BOOKED
                && booking.getStatus() != BoardingBookingStatus.CHECKED_IN) {
            throw new BoardingConflictException("Đặt chỗ đang quá hạn đón hoặc đã kết thúc, không gia hạn được");
        }
        if (!today.isBefore(booking.getCheckOutDate())) {
            throw new BusinessRuleViolationException("BR-LT-06", "Đã tới ngày trả dự kiến, không gia hạn được");
        }
        if (!newOut.isAfter(booking.getCheckOutDate())) {
            throw new InvalidBoardingRequestException("Ngày trả mới phải sau ngày trả hiện tại");
        }
        availability.checkNights(booking.getCheckInDate(), newOut);
        if (branches.openingRangesOn(booking.getBranchId(), newOut).isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-04", "Ngày trả mới trùng ngày chi nhánh không làm việc");
        }
        if (bookings.existsActiveOverlap(booking.getPetId(), booking.getCheckOutDate(), newOut, bookingId)) {
            throw new BusinessRuleViolationException("BR-LT-02", "Thú cưng đã có đặt chỗ lưu trú trùng ngày");
        }
        List<LocalDate> shortNights = capacity.shortNights(booking.getBranchId(), booking.getKennelTypeId(),
                booking.getCheckOutDate(), newOut, today);
        if (!shortNights.isEmpty()) {
            throw new BusinessRuleViolationException("BR-LT-03",
                    "Hết chỗ các đêm: " + BoardingRules.nightList(shortNights));
        }

        booking.extendTo(newOut);
        return assembler.toResponse(bookings.saveAndFlush(booking), today);
    }

    @Transactional
    public BookingResponse cancelBooking(Long bookingId, CancelBookingRequest request) {
        BookingKeys keys = authorize(bookingId);
        boolean customer = "CUSTOMER".equals(branchScope.current().role());
        BoardingBooking booking = bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock);

        transitions.validateTransition(booking.getStatus(), BoardingBookingStatus.CANCELLED);
        if (!today.isBefore(booking.getCheckInDate())) {
            throw new BusinessRuleViolationException("BR-LT-06", "Đã tới ngày nhận, không hủy được");
        }
        boolean late = !now.isBefore(BoardingRules.lateCancelFrom(booking.getCheckInDate(),
                configs.getInt(ConfigKey.BOARDING_LATE_CANCEL_HOURS)));
        booking.cancel(customer ? BookingCancelSource.CUSTOMER : BookingCancelSource.STAFF,
                normalizeReason(request == null ? null : request.reason()), late);
        BoardingBooking saved = bookings.saveAndFlush(booking);
        if (late) {
            // Thời điểm vi phạm = updated_at đã lưu (D3): countViolations đọc đúng cột này nên không đếm lại ở lần sau.
            restrictions.evaluateAfterViolation(keys.getCustomerId(), bookings.findUpdatedAt(bookingId));
        }
        return assembler.toResponse(saved, today);
    }

    /** Khách: chỉ đặt chỗ của hồ sơ mình (404 nếu không); lễ tân: đặt chỗ của chi nhánh mình (403 nếu không). */
    private BookingKeys authorize(Long bookingId) {
        SecurityPrincipal principal = branchScope.current();
        BookingKeys keys = bookings.findKeys(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        if ("CUSTOMER".equals(principal.role())) {
            Long own = customers.findCustomerIdByAccountId(principal.accountId()).orElseThrow(
                    () -> new IllegalStateException("Tài khoản khách #" + principal.accountId() + " không có hồ sơ khách"));
            if (!own.equals(keys.getCustomerId())) {
                throw new ResourceNotFoundException("đặt chỗ lưu trú", bookingId);
            }
        } else {
            branchScope.check(keys.getBranchId());
        }
        return keys;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }
}
