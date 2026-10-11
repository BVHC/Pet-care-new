package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-1 xóa test này cùng {@link AppointmentQueryApiPlaceholder} khi cài thật.
 */
class AppointmentQueryApiPlaceholderTest {

    private final AppointmentQueryApiPlaceholder placeholder = new AppointmentQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findBookedFrom(1L, LocalDate.of(2026, 10, 9)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.existsByPet(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.existsByCustomer(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findBookedByCustomer(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
