package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-2 xóa test này cùng {@link PetQueryApiPlaceholder} khi cài thật.
 */
class PetQueryApiPlaceholderTest {

    private final PetQueryApiPlaceholder placeholder = new PetQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findPet(1L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
