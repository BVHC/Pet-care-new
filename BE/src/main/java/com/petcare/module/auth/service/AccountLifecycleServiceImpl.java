package com.petcare.module.auth.service;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.fsm.AccountTransitionHandler;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.LockReason;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.token.RefreshTokenRevokeReason;
import com.petcare.platform.security.token.TokenIssuanceFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Module 01 (Auth) — thực thi RULE-02-04/07 trên aggregate {@code Account}
 * theo yêu cầu từ module IAM (02). Tái dùng nguyên {@link AccountTransitionHandler}
 * (FSM-1 đã khai báo đủ transition map — docs/convention/backend/05-fsm-pattern.md),
 * không viết FSM mới.
 */
@Service
@RequiredArgsConstructor
public class AccountLifecycleServiceImpl implements AccountLifecycleService {

    private final AccountRepository accountRepository;
    private final AccountTransitionHandler accountTransitionHandler;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final AccountEventRecorder accountEventRecorder;

    @Override
    @Transactional(readOnly = true)
    public AccountSummary getSummary(UUID accountId) {
        return toSummary(findAccount(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, AccountStatus> getStatuses(Collection<UUID> accountIds) {
        return accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getStatus));
    }

    @Override
    @Transactional
    @Auditable(action = "LockAccount")
    public AccountSummary lockAccount(UUID accountId) {
        Account account = findAccount(accountId);
        accountTransitionHandler.validateTransition(account.getStatus(), AccountStatus.LOCKED);

        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.ADMIN_LOCK);
        account.setLockedUntil(null);
        accountRepository.save(account);

        tokenIssuanceFacade.revokeAllSessions(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);
        accountEventRecorder.record(account, "AccountLocked", Map.of("lockReason", LockReason.ADMIN_LOCK.name()));
        return toSummary(account);
    }

    @Override
    @Transactional
    @Auditable(action = "UnlockAccount")
    public AccountSummary unlockAccount(UUID accountId) {
        Account account = findAccount(accountId);
        accountTransitionHandler.validateTransition(account.getStatus(), AccountStatus.ACTIVE);

        account.setStatus(AccountStatus.ACTIVE);
        account.setLockReason(null);
        account.setLockedUntil(null);
        account.setFailedLoginAttempts(0);
        accountRepository.save(account);

        accountEventRecorder.record(account, "AccountUnlocked");
        return toSummary(account);
    }

    @Override
    @Transactional
    @Auditable(action = "DeactivateAccount")
    public AccountSummary deactivateAccount(UUID accountId, String reason) {
        Account account = findAccount(accountId);
        accountTransitionHandler.validateTransition(account.getStatus(), AccountStatus.DEACTIVATED);

        account.setStatus(AccountStatus.DEACTIVATED);
        accountRepository.save(account);

        tokenIssuanceFacade.revokeAllSessions(accountId, RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT);
        accountEventRecorder.record(account, "AccountDeactivated",
                reason == null ? Map.of() : Map.of("reason", reason));
        return toSummary(account);
    }

    @Override
    @Transactional
    @Auditable(action = "ReactivateAccount")
    public AccountSummary reactivateAccount(UUID accountId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("RULE-02-07", "Lý do tái kích hoạt là bắt buộc");
        }
        Account account = findAccount(accountId);
        accountTransitionHandler.validateTransition(account.getStatus(), AccountStatus.ACTIVE);

        account.setStatus(AccountStatus.ACTIVE);
        // FSM-1 cho phép LOCKED -> ACTIVE qua chính endpoint này (không chỉ DEACTIVATED ->
        // ACTIVE) — nếu không reset như unlockAccount(), failedLoginAttempts cũ (vd 5) còn
        // nguyên và 1 lần sai mật khẩu tiếp theo sẽ khoá lại ngay thay vì cần đủ 5 lần.
        account.setLockReason(null);
        account.setLockedUntil(null);
        account.setFailedLoginAttempts(0);
        accountRepository.save(account);

        accountEventRecorder.record(account, "AccountReactivated", Map.of("reason", reason));
        return toSummary(account);
    }

    private Account findAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private AccountSummary toSummary(Account account) {
        return new AccountSummary(account.getId(), account.getStatus());
    }
}
