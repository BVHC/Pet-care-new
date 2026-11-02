package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;
import com.petcare.platform.security.BranchScope;

/**
 * docs/adr/0021, docs/adr/0091: đăng xuất chỉ hủy phiên của token đang dùng (nhân viên tự offline vì online đọc từ phiên
 * chưa hủy, không còn câu xóa {@code last_seen_at}), không ghi audit (không có phụ thuộc {@code AuditRecorder}).
 * Khách cũng đi cùng đường.
 */
class LogoutServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long SESSION = 42L;

    private final SessionService sessions = mock(SessionService.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final LogoutService service = new LogoutService(sessions, branchScope);

    @Test
    void staffRevokesOnlyTheCurrentSession() {
        when(branchScope.current()).thenReturn(principal(Role.VET, 3L));

        service.logout();

        verify(sessions).revoke(SESSION);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void customerGoesThroughSamePath() {
        when(branchScope.current()).thenReturn(principal(Role.CUSTOMER, null));

        service.logout();

        verify(sessions).revoke(SESSION);
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
    void revokeFailureIsNotSwallowed() {
        when(branchScope.current()).thenReturn(principal(Role.VET, 3L));
        doThrow(new IllegalStateException("db down")).when(sessions).revoke(SESSION);

        assertThatThrownBy(service::logout).isInstanceOf(IllegalStateException.class);
    }

    private static AccountPrincipal principal(Role role, Long branchId) {
        return new AccountPrincipal(ACCOUNT, "user@petcare.test", SESSION, role, branchId, false);
    }
}
