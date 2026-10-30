package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

class StockAlertRecipientsTest {

    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final StockAlertRecipients recipients = new StockAlertRecipients(staff);

    @Test
    void activeBranchManagersComeFirst() {
        when(staff.findActiveStaffIds(7L, Role.BRANCH_MANAGER)).thenReturn(List.of(11L, 12L));

        assertThat(recipients.forBranch(7L)).containsExactly(11L, 12L);
        verify(staff, never()).findActiveSuperManagerIds();
    }

    @Test
    void fallsBackToSuperManagersWhenBranchHasNoManager() {
        when(staff.findActiveStaffIds(7L, Role.BRANCH_MANAGER)).thenReturn(List.of());
        when(staff.findActiveSuperManagerIds()).thenReturn(List.of(1L));

        assertThat(recipients.forBranch(7L)).containsExactly(1L);
    }
}
