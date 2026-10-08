package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-1 xóa test này cùng {@link VaccinationQueryApiPlaceholder} khi cài thật.
 */
class VaccinationQueryApiPlaceholderTest {

    private final VaccinationQueryApiPlaceholder placeholder = new VaccinationQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findBoardingVaccineGaps(1L, LocalDate.of(2026, 10, 8)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.existsByVaccineType(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
