package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.BookingFilter;
import com.petcare.module.boarding.dto.BookingResponse;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.exception.InvalidBoardingRequestException;
import com.petcare.module.boarding.repository.BoardingBookingListRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Xem đặt chỗ (UC58, UC59, UC60, boarding-v1 #9–10; docs/adr/0065). Khách chỉ thấy đặt chỗ của hồ sơ mình (404 với của
 * người khác, như thể không tồn tại — ADR-0006); nhân viên chỉ chi nhánh mình. Chỉ đọc, không khóa.
 */
@Service
public class BoardingViewService {

    static final int MAX_PAGE_SIZE = 100;

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final BoardingBookingRepository bookings;
    private final BoardingBookingListRepository lists;
    private final BoardingAssembler assembler;
    private final Clock clock;

    public BoardingViewService(BranchScope branchScope, CustomerQueryApi customers, BoardingBookingRepository bookings,
            BoardingBookingListRepository lists, BoardingAssembler assembler, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.bookings = bookings;
        this.lists = lists;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** Đếm và lấy trang cùng snapshot (REPEATABLE READ, readOnly). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<BookingResponse> listBookings(BookingFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new InvalidBoardingRequestException("Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc");
        }
        SecurityPrincipal principal = branchScope.current();
        boolean customer = "CUSTOMER".equals(principal.role());
        Long ownerCustomerId = customer ? ownCustomerId(principal.accountId()) : null;
        Long branchId = customer ? null : branchScope.resolve(null);
        LocalDate today = LocalDate.now(clock);

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        long total = lists.count(branchId, ownerCustomerId, filter, today);
        List<Long> ids = total == 0 ? List.of()
                : lists.findPageIds(branchId, ownerCustomerId, filter, today, pageable.getOffset(),
                        pageable.getPageSize());
        Map<Long, BoardingBooking> byId = bookings.findAllById(ids).stream()
                .collect(Collectors.toMap(BoardingBooking::getId, Function.identity()));
        List<BoardingBooking> ordered = ids.stream().map(byId::get).toList();
        return PageResponse.of(new PageImpl<>(assembler.toResponses(ordered, today), pageable, total));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Long bookingId) {
        SecurityPrincipal principal = branchScope.current();
        BoardingBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        if ("CUSTOMER".equals(principal.role())) {
            if (!booking.getCustomerId().equals(ownCustomerId(principal.accountId()))) {
                throw new ResourceNotFoundException("đặt chỗ lưu trú", bookingId);
            }
        } else {
            branchScope.check(booking.getBranchId());
        }
        return assembler.toResponse(booking, LocalDate.now(clock));
    }

    private Long ownCustomerId(Long accountId) {
        return customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }
}
