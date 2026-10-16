package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link OrderQueryApiPlaceholder} khi cài thật.
 */
class OrderQueryApiPlaceholderTest {

    private final OrderQueryApiPlaceholder placeholder = new OrderQueryApiPlaceholder(null, null, null);

    @Test
    void methodsNotImplementedYetThrow() {
        // existsByCustomer, hasPendingOrderForPet đã cài thật một phần (T17): CrossModuleQueryIT
        assertThatThrownBy(() -> placeholder.findUnpaidOrdersByCustomer(1L))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("OrderQueryApi");
    }
}
