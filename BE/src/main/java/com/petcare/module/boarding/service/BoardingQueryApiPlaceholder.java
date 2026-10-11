package com.petcare.module.boarding.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;

/**
 * Giữ chỗ cho {@link BoardingQueryApi} tới khi boarding (BE-2) cài thật. Theo 06-module-contracts §1: mọi method
 * ném lỗi, không trả giá trị "an toàn" (trả rỗng sẽ cho thu hẹp giờ mở cửa qua mặt BR-CN-04). Hệ quả: đặt giờ mở
 * cửa, thêm ngày nghỉ và hai endpoint xem trước ảnh hưởng của branch trả 500.
 * BE-2 xóa lớp này (cùng {@code BoardingQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class BoardingQueryApiPlaceholder implements BoardingQueryApi {

    static final String MESSAGE = "BoardingQueryApi chưa được cài (module boarding, BE-2)";

    @Override
    public Optional<StaySummary> findBooking(Long bookingId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean isPetInStay(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<BookedStay> findBookedFrom(Long branchId, LocalDate fromDate) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public int countViolations(Long customerId, Instant since) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<StaySummary> findActiveStaysByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
