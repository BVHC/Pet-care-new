package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.repository.BranchRepository;

/**
 * {@code BranchApi.lockForStaffChange} (docs/adr/0029, nợ D014): khóa theo id tăng dần dù bên gọi truyền thứ tự nào,
 * id lạ vắng khỏi map, transaction {@code readOnly} bị chặn trước mọi câu SQL. Khóa thật trên PostgreSQL:
 * {@code BranchStaffLockIT}.
 */
class BranchStaffLockServiceTest {

    private final BranchRepository branches = mock(BranchRepository.class);
    private final BranchStaffLockService service = new BranchStaffLockService(branches);

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void locksInAscendingIdOrderAndSkipsUnknownIds() {
        when(branches.lockStatusForStaffChange(3L)).thenReturn(Optional.of("ACTIVE"));
        when(branches.lockStatusForStaffChange(1L)).thenReturn(Optional.of("DRAFT"));
        when(branches.lockStatusForStaffChange(2L)).thenReturn(Optional.empty());

        var locked = service.lockForStaffChange(List.of(3L, 1L, 2L, 3L));

        InOrder order = inOrder(branches);
        order.verify(branches).lockStatusForStaffChange(1L);
        order.verify(branches).lockStatusForStaffChange(2L);
        order.verify(branches).lockStatusForStaffChange(3L);
        order.verifyNoMoreInteractions();
        assertThat(locked).containsExactly(
                java.util.Map.entry(1L, BranchStatus.DRAFT), java.util.Map.entry(3L, BranchStatus.ACTIVE));
    }

    @Test
    void readOnlyTransactionIsRejectedBeforeAnySql() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.lockForStaffChange(List.of(1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        verify(branches, never()).lockStatusForStaffChange(anyLong());
    }

    @Test
    void emptyInputLocksNothing() {
        assertThat(service.lockForStaffChange(List.of())).isEmpty();
        verify(branches, never()).lockStatusForStaffChange(anyLong());
    }
}
