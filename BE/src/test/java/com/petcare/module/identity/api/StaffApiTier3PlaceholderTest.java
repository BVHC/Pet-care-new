package com.petcare.module.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/** Tầng 3 của identity (T55b, docs/06 §6b hàng 1, 13; docs/adr/0113). */
class StaffApiTier3PlaceholderTest {

    @Test
    void countActiveByBranchThrowsUntilImplemented() {
        StaffDirectoryApi staff = mock(StaffDirectoryApi.class, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> staff.countActiveByBranch(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("StaffDirectoryApi.countActiveByBranch")
                .hasMessageContaining("T75");
    }

    /** Chỉ khai báo lớp (docs/06 §6b hàng 1); chưa có nơi phát nên chưa có test phát. */
    @Test
    void staffLeftBranchEventCarriesStaffAndBranch() {
        StaffLeftBranchEvent event = new StaffLeftBranchEvent(7L, 2L);

        assertThat(event.staffId()).isEqualTo(7L);
        assertThat(event.branchId()).isEqualTo(2L);
        assertThat(event).isEqualTo(new StaffLeftBranchEvent(7L, 2L));
    }
}
