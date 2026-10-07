package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Nợ D001: mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link CustomerApiPlaceholder} khi cài thật.
 */
class CustomerApiPlaceholderTest {

    private final CustomerApiPlaceholder placeholder = new CustomerApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.createOnlineProfile(1L, "A", null))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("D001");
        assertThatThrownBy(() -> placeholder.flagLinkDecisionIfPhoneMatches(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.deleteOnlineProfileOfUnverifiedAccount(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.linkAccountToCounterProfile(1L, 2L, "a@petcare.test", 3L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.declineLink(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
