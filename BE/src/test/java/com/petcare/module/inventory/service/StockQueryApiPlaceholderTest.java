package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link StockQueryApiPlaceholder} khi cài thật.
 */
class StockQueryApiPlaceholderTest {

    private final StockQueryApiPlaceholder placeholder = new StockQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.availableQuantity(1L, 2L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.branchIdsWithAvailableStock(2L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.hasStockAnywhere(2L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
