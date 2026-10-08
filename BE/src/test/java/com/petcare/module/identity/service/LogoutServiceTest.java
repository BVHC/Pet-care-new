package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.platform.security.BranchScope;

/**
 * docs/adr/0021: đăng xuất xóa {@code last_seen_at} <b>trước</b> rồi mới hủy phiên (thứ tự khóa
 * {@code accounts → sessions}), chỉ phiên của token đang dùng, không ghi audit (không có phụ thuộc
 * {@code AuditRecorder}). Khách cũng đi cùng đường: câu SQL tự bỏ qua dòng đã {@code NULL}.
 */
class LogoutServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long SESSION = 42L;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final LogoutService service = new LogoutService(accounts, sessions, branchScope);

    @Test
    void staffClearsLastSeenBeforeRevokingCurrentSession() {
        when(branchScope.current()).thenReturn(principal(Role.VET, 3L));

        service.logout();

        InOrder order = inOrder(accounts, sessions);
        order.verify(accounts).clearLastSeen(ACCOUNT);
        order.verify(sessions).revoke(SESSION);
        verifyNoMoreInteractions(accounts, sessions);
    }

    @Test
    void customerGoesThroughSamePath() {
        when(branchScope.current()).thenReturn(principal(Role.CUSTOMER, null));

        service.logout();

        InOrder order = inOrder(accounts, sessions);
        order.verify(accounts).clearLastSeen(ACCOUNT);
        order.verify(sessions).revoke(SESSION);
    }

    @Test
    void revokesOnlyTheCurrentSessionNeverOthers() {
        when(branchScope.current()).thenReturn(principal(Role.ADMIN, null));

        service.logout();

        verify(sessions).revoke(SESSION);
        verify(sessions, never()).revokeAll(anyLong());
        verify(sessions, never()).revokeOthers(anyLong(), anyLong());
    }

    @Test
    void failureToClearLastSeenNeverRevokes() {
        when(branchScope.current()).thenReturn(principal(Role.VET, 3L));
        when(accounts.clearLastSeen(ACCOUNT)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(service::logout).isInstanceOf(IllegalStateException.class);

        verify(sessions, never()).revoke(anyLong());
    }

    private static AccountPrincipal principal(Role role, Long branchId) {
        return new AccountPrincipal(ACCOUNT, "user@petcare.test", SESSION, role, branchId, false);
    }
}
