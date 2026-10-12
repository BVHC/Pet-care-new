package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Nợ D016: placeholder phải ném, không trả {@code false} "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link CashierShiftQueryApiPlaceholder} khi cài thật.
 */
class CashierShiftQueryApiPlaceholderTest {

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> new CashierShiftQueryApiPlaceholder().findOpenShift(1L))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("D016");
    }
}
