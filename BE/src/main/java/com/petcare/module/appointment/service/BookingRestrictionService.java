package com.petcare.module.appointment.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;

/**
 * Cài {@link BookingRestrictionApi} (BR-LH-09). T16 phần 2 (docs/adr/0043) chỉ cài phần đọc {@link #isRestricted}; phần
 * ghi {@link #evaluateAfterViolation} ném lỗi tới khi hủy muộn (phần 4) / ST05 có — nợ D030 (06-module-contracts §1:
 * không trả giá trị "an toàn"; hiện chưa có bên gọi nào).
 */
@Service
public class BookingRestrictionService implements BookingRestrictionApi {

    static final String EVALUATE_NOT_READY = "BookingRestrictionApi.evaluateAfterViolation chưa được cài "
            + "(module appointment, T16 phần 4 / ST05)";

    private final BookingRestrictionRepository restrictions;

    public BookingRestrictionService(BookingRestrictionRepository restrictions) {
        this.restrictions = restrictions;
    }

    /** {@code starts_at ≤ at < ends_at} và chưa gỡ sớm (erd §5). Tham gia transaction của bên gọi nếu có. */
    @Override
    @Transactional(readOnly = true)
    public boolean isRestricted(Long customerId, Instant at) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
        return restrictions.existsActive(customerId, at);
    }

    @Override
    public void evaluateAfterViolation(Long customerId, Instant at) {
        throw new UnsupportedOperationException(EVALUATE_NOT_READY);
    }
}
