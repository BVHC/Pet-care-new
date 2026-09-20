package com.petcare.module.auth.service;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.fsm.AccountTransitionHandler;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.platform.audit.AuditDetail;
import com.petcare.platform.audit.AuditOrganizationId;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.AuditStoreId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.LockReason;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
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
    @Auditable(action = "LockAccount", resourceType = "Account")
    public AccountSummary lockAccount(@AuditResourceId UUID accountId,
                                       @AuditOrganizationId UUID organizationId, @AuditStoreId UUID storeId) {
        Account account = findAccount(accountId);
        AccountStatus previousStatus = account.getStatus();
        // docs/api/iam-v1.md §B "Idempotent (đã LOCKED vẫn 200)" — StateMachineBase không có
        // self-loop (LOCKED -> LOCKED không nằm trong allowedTransitions()) nên phải short-circuit
        // tường minh ở đây, nếu không validateTransition ném InvalidStateTransitionException (409)
        // thay vì trả 200 như contract. Giữ nguyên lock_reason hiện có (không ép về ADMIN_LOCK) —
        // gọi lại không được coi là một lần khóa mới.
        if (previousStatus == AccountStatus.LOCKED) {
            return toSummary(account);
        }
        accountTransitionHandler.validateTransition(previousStatus, AccountStatus.LOCKED);

        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.ADMIN_LOCK);
        account.setLockedUntil(null);
        accountRepository.save(account);

        tokenIssuanceFacade.revokeAllSessions(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);
        accountEventRecorder.record(account, "AccountLocked", Map.of("lockReason", LockReason.ADMIN_LOCK.name()));
        return new AccountSummary(account.getId(), previousStatus, account.getStatus());
    }

    @Override
    @Transactional
    @Auditable(action = "UnlockAccount", resourceType = "Account")
    public AccountSummary unlockAccount(@AuditResourceId UUID accountId,
                                         @AuditOrganizationId UUID organizationId, @AuditStoreId UUID storeId) {
        Account account = findAccount(accountId);
        AccountStatus previousStatus = account.getStatus();
        // docs/api/iam-v1.md §B "Idempotent" — account đã ACTIVE sẵn thì trả 200 no-op thay vì
        // rơi vào guard DEACTIVATED bên dưới (vốn chỉ áp dụng cho nguồn KHÔNG phải LOCKED/ACTIVE).
        if (previousStatus == AccountStatus.ACTIVE) {
            return toSummary(account);
        }
        // FSM-1 (docs/03-state-machines.md §1) chỉ vẽ LOCKED -> ACTIVE qua UnlockAccount;
        // DEACTIVATED -> ACTIVE là cạnh riêng của ReactivateAccount (bắt buộc `reason` theo
        // RULE-02-07). accountTransitionHandler.validateTransition() chỉ kiểm tra theo CẶP
        // TRẠNG THÁI (dùng chung cho mọi lệnh tới ACTIVE) nên không tự chặn được việc gọi
        // UnlockAccount trên 1 tài khoản đang DEACTIVATED — phải chặn tường minh ở đây để
        // không "mở khóa" tắt qua endpoint sai, né mất yêu cầu ghi lý do bắt buộc.
        if (previousStatus != AccountStatus.LOCKED) {
            throw new InvalidStateTransitionException(accountTransitionHandler.getClass().getSimpleName(),
                    previousStatus.name(), AccountStatus.ACTIVE.name());
        }
        accountTransitionHandler.validateTransition(previousStatus, AccountStatus.ACTIVE);

        account.setStatus(AccountStatus.ACTIVE);
        account.setLockReason(null);
        account.setLockedUntil(null);
        account.setFailedLoginAttempts(0);
        accountRepository.save(account);

        accountEventRecorder.record(account, "AccountUnlocked");
        return new AccountSummary(account.getId(), previousStatus, account.getStatus());
    }

    @Override
    @Transactional
    @Auditable(action = "DeactivateAccount", resourceType = "Account")
    public AccountSummary deactivateAccount(@AuditResourceId UUID accountId, @AuditDetail("reason") String reason,
                                             @AuditOrganizationId UUID organizationId, @AuditStoreId UUID storeId) {
        Account account = findAccount(accountId);
        AccountStatus previousStatus = account.getStatus();
        // docs/api/iam-v1.md §B "Idempotent" — xem giải thích ở lockAccount().
        if (previousStatus == AccountStatus.DEACTIVATED) {
            return toSummary(account);
        }
        accountTransitionHandler.validateTransition(previousStatus, AccountStatus.DEACTIVATED);

        account.setStatus(AccountStatus.DEACTIVATED);
        accountRepository.save(account);

        tokenIssuanceFacade.revokeAllSessions(accountId, RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT);
        accountEventRecorder.record(account, "AccountDeactivated",
                reason == null ? Map.of() : Map.of("reason", reason));
        return new AccountSummary(account.getId(), previousStatus, account.getStatus());
    }

    @Override
    @Transactional
    @Auditable(action = "ReactivateAccount", resourceType = "Account")
    public AccountSummary reactivateAccount(@AuditResourceId UUID accountId, @AuditDetail("reason") String reason,
                                             @AuditOrganizationId UUID organizationId, @AuditStoreId UUID storeId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("RULE-02-07", "Lý do tái kích hoạt là bắt buộc");
        }
        Account account = findAccount(accountId);
        AccountStatus previousStatus = account.getStatus();
        // docs/api/iam-v1.md §B "Idempotent" — xem giải thích ở lockAccount(); reason vẫn được
        // validate ở trên TRƯỚC khi biết account đã ACTIVE hay chưa (giữ nguyên yêu cầu RULE-02-07
        // bất kể có phải no-op hay không).
        if (previousStatus == AccountStatus.ACTIVE) {
            return toSummary(account);
        }
        // FSM-1/docs/api/iam-v1.md C4: ReactivateAccount CHỈ hợp lệ từ DEACTIVATED hoặc LOCKED.
        // accountTransitionHandler.validateTransition() kiểm tra theo CẶP TRẠNG THÁI dùng chung
        // cho mọi lệnh tới ACTIVE, và PENDING_VERIFICATION -> ACTIVE vốn là cạnh hợp lệ của
        // VerifyOTP — nếu không chặn tường minh ở đây, gọi Reactivate trên tài khoản đang chờ xác
        // thực OTP sẽ kích hoạt thẳng, bỏ qua VerifyOTP (RULE-01-03), giống lỗi đã né ở unlockAccount().
        if (previousStatus != AccountStatus.DEACTIVATED && previousStatus != AccountStatus.LOCKED) {
            throw new InvalidStateTransitionException(accountTransitionHandler.getClass().getSimpleName(),
                    previousStatus.name(), AccountStatus.ACTIVE.name());
        }
        accountTransitionHandler.validateTransition(previousStatus, AccountStatus.ACTIVE);

        account.setStatus(AccountStatus.ACTIVE);
        // FSM-1 cho phép LOCKED -> ACTIVE qua chính endpoint này (không chỉ DEACTIVATED ->
        // ACTIVE) — nếu không reset như unlockAccount(), failedLoginAttempts cũ (vd 5) còn
        // nguyên và 1 lần sai mật khẩu tiếp theo sẽ khoá lại ngay thay vì cần đủ 5 lần.
        account.setLockReason(null);
        account.setLockedUntil(null);
        account.setFailedLoginAttempts(0);
        accountRepository.save(account);

        accountEventRecorder.record(account, "AccountReactivated", Map.of("reason", reason));
        return new AccountSummary(account.getId(), previousStatus, account.getStatus());
    }

    private Account findAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }

    private AccountSummary toSummary(Account account) {
        return new AccountSummary(account.getId(), account.getStatus());
    }
}
