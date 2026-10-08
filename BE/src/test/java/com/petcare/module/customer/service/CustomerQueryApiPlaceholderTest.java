package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Nợ D010: mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link CustomerQueryApiPlaceholder} khi cài thật.
 */
class CustomerQueryApiPlaceholderTest {

    private final CustomerQueryApiPlaceholder placeholder = new CustomerQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findContact(1L))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("D010");
        assertThatThrownBy(() -> placeholder.findCustomerIdByAccountId(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findLinkCandidates("0901234567"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
