package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.OtpTokenRepository;
import com.petcare.module.identity.service.PendingAccountCleanupService.PurgeResult;

/**
 * ST02, Tài khoản#3 (BR-TK-08, docs/adr/0013): khóa và kiểm lại trước mọi lần ghi; thứ tự xóa theo FK
 * ({@code otp_tokens} → hồ sơ online → {@code accounts}); không khóa được thì không chạm gì; lỗi đi thẳng lên để
 * transaction của tài khoản đó rollback.
 */
class PendingAccountCleanupServiceTest {

    private static final long ACCOUNT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OtpTokenRepository otpTokens = mock(OtpTokenRepository.class);
    private final CustomerApi customers = mock(CustomerApi.class);
    private final PendingAccountCleanupService service = new PendingAccountCleanupService(accounts, otpTokens,
            customers);

    @Test
    void locksThenDeletesOtpThenProfileThenAccount() {
        when(accounts.lockExpiredPending(ACCOUNT_ID, NOW)).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.deletePendingById(ACCOUNT_ID)).thenReturn(1);

        assertThat(service.purge(ACCOUNT_ID, NOW)).isEqualTo(PurgeResult.DELETED);

        InOrder order = inOrder(accounts, otpTokens, customers);
        order.verify(accounts).lockExpiredPending(ACCOUNT_ID, NOW);
        order.verify(otpTokens).deleteByAccountId(ACCOUNT_ID);
        order.verify(customers).deleteOnlineProfileOfUnverifiedAccount(ACCOUNT_ID);
        order.verify(accounts).deletePendingById(ACCOUNT_ID);
    }

    /** Đã xác thực, đã bị lượt khác xóa, hoặc verify/resend đang giữ khóa ({@code SKIP LOCKED}): không ghi gì. */
    @Test
    void notLockedMeansSkippedAndNothingIsDeleted() {
        when(accounts.lockExpiredPending(ACCOUNT_ID, NOW)).thenReturn(Optional.empty());

        assertThat(service.purge(ACCOUNT_ID, NOW)).isEqualTo(PurgeResult.SKIPPED);

        verifyNoInteractions(otpTokens, customers);
        verify(accounts, never()).deletePendingById(anyLong());
    }

    /** Lỗi phía customer (placeholder D001…) đi thẳng lên: transaction rollback cả {@code otp_tokens} đã xóa. */
    @Test
    void customerApiFailurePropagatesBeforeAccountDelete() {
        when(accounts.lockExpiredPending(ACCOUNT_ID, NOW)).thenReturn(Optional.of(ACCOUNT_ID));
        doThrow(new UnsupportedOperationException("nợ D001")).when(customers)
                .deleteOnlineProfileOfUnverifiedAccount(ACCOUNT_ID);

        assertThatThrownBy(() -> service.purge(ACCOUNT_ID, NOW)).isInstanceOf(UnsupportedOperationException.class);

        verify(accounts, never()).deletePendingById(anyLong());
    }

    @Test
    void lockedButNotDeletedIsAnError() {
        when(accounts.lockExpiredPending(ACCOUNT_ID, NOW)).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.deletePendingById(ACCOUNT_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.purge(ACCOUNT_ID, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findExpiredIdsDelegatesKeysetQuery() {
        when(accounts.findExpiredPendingIds(NOW, 40L, 200)).thenReturn(List.of(41L, 42L));

        assertThat(service.findExpiredIds(NOW, 40L, 200)).containsExactly(41L, 42L);
    }
}
