package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Nợ D016: mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-1 xóa test này cùng {@link VisitQueryApiPlaceholder} khi cài thật (T26).
 */
class VisitQueryApiPlaceholderTest {

    private final VisitQueryApiPlaceholder placeholder = new VisitQueryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findVisit(1L))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("D016");
        assertThatThrownBy(() -> placeholder.findUnfinishedVisitIdsAssignedTo(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.hasOpenVisit(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.existsByPet(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findVisitIdsByPet(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.hasMedicalHistory(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
