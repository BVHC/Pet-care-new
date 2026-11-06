package com.petcare.module.care.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/** Placeholder tầng 3 của care (T55b, docs/06 §6b hàng 13; docs/adr/0113). */
class CareTaskApiTier3PlaceholderTest {

    @Test
    void reassignBranchThrowsUntilImplemented() {
        CareTaskApi tasks = mock(CareTaskApi.class, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> tasks.reassignBranch(1L, 2L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CareTaskApi.reassignBranch")
                .hasMessageContaining("T75");
    }
}
