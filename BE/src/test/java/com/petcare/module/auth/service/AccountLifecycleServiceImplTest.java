package com.petcare.module.auth.service;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.fsm.AccountTransitionHandler;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.LockReason;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.token.RefreshTokenRevokeReason;
import com.petcare.platform.security.token.TokenIssuanceFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RULE-02-04/07 (docs/02-business-rules.md), FSM-1 (docs/03-state-machines.md §1). */
@ExtendWith(MockitoExtension.class)
class AccountLifecycleServiceImplTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TokenIssuanceFacade tokenIssuanceFacade;
    @Mock
    private AccountEventRecorder accountEventRecorder;

    private final AccountTransitionHandler accountTransitionHandler = new AccountTransitionHandler();

    private AccountLifecycleServiceImpl service;

    private static Account account(AccountStatus status) {
        Account account = new Account("staff@example.com", null, "hashed");
        account.setId(UUID.randomUUID());
        account.setStatus(status);
        return account;
    }

    @BeforeEach
    void setUp() {
        service = new AccountLifecycleServiceImpl(accountRepository, accountTransitionHandler,
                tokenIssuanceFacade, accountEventRecorder);
    }

    @Test
    void getSummary_notFound_throwsResourceNotFound() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSummary(accountId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lockAccount_active_locksAndRevokesSessions_RULE_02_04() {
        Account account = account(AccountStatus.ACTIVE);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        var summary = service.lockAccount(account.getId(), orgId, storeId);

        assertThat(summary.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(summary.previousStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getLockReason()).isEqualTo(LockReason.ADMIN_LOCK);
        verify(tokenIssuanceFacade).revokeAllSessions(account.getId(), RefreshTokenRevokeReason.LOCK_ACCOUNT);
        verify(accountEventRecorder).record(eq(account), eq("AccountLocked"), any());
    }

    @Test
    void lockAccount_alreadyDeactivated_throwsInvalidTransition() {
        Account account = account(AccountStatus.DEACTIVATED);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.lockAccount(account.getId(), null, null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(tokenIssuanceFacade, never()).revokeAllSessions(any(), any());
    }

    @Test
    void unlockAccount_locked_unlocksRegardlessOfLockReason_FSM1() {
        // FSM-1 CONFIRMED: Admin unlock áp dụng mọi lock_reason, không cần chờ locked_until.
        Account account = account(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(10));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var summary = service.unlockAccount(account.getId(), null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(summary.previousStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.getLockReason()).isNull();
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void unlockAccount_deactivated_throwsInvalidTransition_notReactivatedWithoutReason() {
        // FSM-1: DEACTIVATED -> ACTIVE chỉ hợp lệ qua ReactivateAccount (bắt buộc reason,
        // RULE-02-07), không qua UnlockAccount — dù transition map dùng chung cho phép cặp
        // trạng thái này (vì đó cũng là cạnh hợp lệ của ReactivateAccount).
        Account account = account(AccountStatus.DEACTIVATED);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.unlockAccount(account.getId(), null, null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(accountRepository, never()).save(any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountUnlocked"));
    }

    @Test
    void deactivateAccount_fromLocked_deactivatesAndRevokesSessions_RULE_02_07() {
        Account account = account(AccountStatus.LOCKED);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var summary = service.deactivateAccount(account.getId(), "Nghỉ việc", null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.DEACTIVATED);
        assertThat(summary.previousStatus()).isEqualTo(AccountStatus.LOCKED);
        verify(tokenIssuanceFacade).revokeAllSessions(account.getId(), RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT);
    }

    @Test
    void reactivateAccount_missingReason_throwsBusinessRuleViolation_RULE_02_07() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reactivateAccount(accountId, " ", null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-02-07"));
        verify(accountRepository, never()).findById(any());
    }

    @Test
    void reactivateAccount_withReason_reactivates_RULE_02_07() {
        Account account = account(AccountStatus.DEACTIVATED);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var summary = service.reactivateAccount(account.getId(), "Quay lại làm việc", null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountEventRecorder).record(eq(account), eq("AccountReactivated"), any());
    }

    @Test
    void reactivateAccount_fromLocked_resetsFailedLoginCounterAndLockReason() {
        // FSM-1 cho phép LOCKED -> ACTIVE qua reactivate (không chỉ qua unlock) — nếu không
        // reset failedLoginAttempts/lockReason/lockedUntil như unlockAccount(), account bị
        // khoá lại chỉ sau 1 lần sai mật khẩu tiếp theo thay vì đủ 5 lần.
        Account account = account(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(10));
        account.setFailedLoginAttempts(5);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        var summary = service.reactivateAccount(account.getId(), "Mở khoá lại sau xác minh", null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getLockReason()).isNull();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getFailedLoginAttempts()).isEqualTo(0);
    }

    // docs/api/iam-v1.md §B khai báo cả 4 lifecycle action là "Idempotent" — gọi lại trên
    // account đã ở đúng trạng thái đích phải trả 200 no-op, không ném InvalidStateTransitionException.

    @Test
    void lockAccount_alreadyLocked_isIdempotent_noSideEffects() {
        Account account = account(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        var summary = service.lockAccount(account.getId(), null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(summary.previousStatus()).isEqualTo(AccountStatus.LOCKED);
        // Không "khóa lại" — giữ nguyên lock_reason cũ, không re-revoke/re-emit event.
        assertThat(account.getLockReason()).isEqualTo(LockReason.AUTO_FAILED_LOGIN);
        verify(accountRepository, never()).save(any());
        verify(tokenIssuanceFacade, never()).revokeAllSessions(any(), any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountLocked"), any());
    }

    @Test
    void unlockAccount_alreadyActive_isIdempotent_noSideEffects() {
        Account account = account(AccountStatus.ACTIVE);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        var summary = service.unlockAccount(account.getId(), null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository, never()).save(any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountUnlocked"));
    }

    @Test
    void deactivateAccount_alreadyDeactivated_isIdempotent_noSideEffects() {
        Account account = account(AccountStatus.DEACTIVATED);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        var summary = service.deactivateAccount(account.getId(), "Nghỉ việc", null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.DEACTIVATED);
        verify(accountRepository, never()).save(any());
        verify(tokenIssuanceFacade, never()).revokeAllSessions(any(), any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountDeactivated"), any());
    }

    @Test
    void reactivateAccount_alreadyActive_isIdempotent_noSideEffects() {
        Account account = account(AccountStatus.ACTIVE);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        var summary = service.reactivateAccount(account.getId(), "Xac nhan lai", null, null);

        assertThat(summary.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository, never()).save(any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountReactivated"), any());
    }

    @Test
    void reactivateAccount_fromPendingVerification_throwsInvalidTransition_doesNotBypassOtp() {
        // FSM-1/docs/api/iam-v1.md C4: Reactivate chỉ hợp lệ từ DEACTIVATED/LOCKED. Transition
        // map dùng chung có cạnh PENDING_VERIFICATION -> ACTIVE (của VerifyOTP) — Reactivate
        // không được "mượn" cạnh đó để kích hoạt tài khoản chưa xác thực OTP.
        Account account = account(AccountStatus.PENDING_VERIFICATION);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.reactivateAccount(account.getId(), "Ly do bat ky", null, null))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(accountRepository, never()).save(any());
        verify(accountEventRecorder, never()).record(any(), eq("AccountReactivated"), any());
    }

    @Test
    void reactivateAccount_alreadyActive_stillRequiresReason_RULE_02_07() {
        // No-op không miễn trừ yêu cầu reason bắt buộc (RULE-02-07) — validate trước khi biết
        // account đã ACTIVE hay chưa.
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> service.reactivateAccount(accountId, " ", null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(accountRepository, never()).findById(any());
    }
}
