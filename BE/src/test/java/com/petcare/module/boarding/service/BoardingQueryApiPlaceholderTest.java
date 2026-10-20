package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link BoardingQueryApiPlaceholder} khi cài thật.
 */
class BoardingQueryApiPlaceholderTest {

    private final BoardingQueryApiPlaceholder placeholder = new BoardingQueryApiPlaceholder(null);

    @Test
    void methodsNotImplementedYetThrow() {
        // isPetInStay, existsByPet, findBookingIdsByPet, existsByCustomer (T17) và findBooking (T29) đã cài thật một
        // phần: CrossModuleQueryIT
        assertThatThrownBy(() -> placeholder.findBookedFrom(1L, LocalDate.of(2026, 10, 9)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.countViolations(1L, Instant.EPOCH))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findActiveStaysByCustomer(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
