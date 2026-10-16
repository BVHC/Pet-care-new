package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Nợ D016: mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-1 xóa test này cùng {@link VisitQueryApiPlaceholder} khi cài thật (T26).
 */
class VisitQueryApiPlaceholderTest {

    private final VisitQueryApiPlaceholder placeholder = new VisitQueryApiPlaceholder(null);

    @Test
    void methodsNotImplementedYetThrow() {
        assertThatThrownBy(() -> placeholder.findUnfinishedVisitsAssignedTo(1L))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("D016");
        // findVisit (T19), hasOpenVisit, existsByPet, findVisitIdsByPet, hasMedicalHistory đã cài thật một phần: CrossModuleQueryIT
    }
}
