package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.AccountLockedEvent;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LockResult;
import com.petcare.module.identity.dto.PendingWorkItem;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.AccountAdminMapper;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.identity.service.AccountLockNotices.LockedAccount;
import com.petcare.module.identity.service.PendingWorkCollector.CustomerWork;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.extern.slf4j.Slf4j;

/**
 * UC09 — ADMIN khóa / mở khóa tài khoản bất kỳ (identity-v1 #29, #30; 03 Tài khoản#7, #8; BR-QT-04, 11, 12, BR-TK-11,
 * BR-TN-08; docs/adr/0035). Mỗi method một transaction trọn use case; không cần facade (không BCrypt, không 403 phân cấp
 * cần audit sau rollback — UC09 chỉ dùng {@code @PreAuthorize}, docs/adr/0029). Không bắt exception nào bên trong; không
 * {@code noRollbackFor}: khóa không có gì phải giữ lại khi rollback.
 * <p>
 * Khóa: kiểm không tự khóa → đọc role không khóa → (BRANCH_MANAGER) khóa {@code branches} → khóa {@code accounts} → đọc
 * hồ sơ → đã khóa (409) → <b>đọc</b> việc dở dang và người nhận → <b>ghi</b>: cờ khóa, hủy mọi phiên, phát
 * {@link AccountLockedEvent}, thông báo trong app, audit → {@code flush}. Thứ tự khóa {@code branches → accounts →
 * sessions} (docs/adr/0029 mục 4, docs/adr/0021).
 */
@Slf4j
@Service
public class AccountLockService {

    private final AccountRepository accounts;
    private final StaffProfileRepository staffProfiles;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final PendingWorkCollector pendingWork;
    private final SessionService sessions;
    private final ApplicationEventPublisher events;
    private final NotificationApi notifications;
    private final AuditRecorder audit;
    private final AccountAdminMapper mapper;
    private final LoginMapper loginMapper;
    private final BranchScope branchScope;
    private final Clock clock;

    public AccountLockService(AccountRepository accounts, StaffProfileRepository staffProfiles, BranchApi branchLocks,
            BranchQueryApi branches, PendingWorkCollector pendingWork, SessionService sessions,
            ApplicationEventPublisher events, NotificationApi notifications, AuditRecorder audit,
            AccountAdminMapper mapper, LoginMapper loginMapper, BranchScope branchScope, Clock clock) {
        this.accounts = accounts;
        this.staffProfiles = staffProfiles;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.pendingWork = pendingWork;
        this.sessions = sessions;
        this.events = events;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
        this.loginMapper = loginMapper;
        this.branchScope = branchScope;
        this.clock = clock;
    }

    /**
     * Tài khoản#7 (identity-v1 #29). Mọi lỗi (400 tự khóa, 404, 409 đã khóa / chi nhánh đổi, 500 interface chưa cài) ném
     * trước lệnh ghi đầu tiên; lỗi ở bước ghi (phiên, sự kiện, outbox, audit) rollback cả cờ khóa đã flush.
     */
    @Transactional
    public LockResult lockAccount(Long accountId, String rawReason) {
        SecurityPrincipal actor = branchScope.current();
        if (accountId.equals(actor.accountId())) {
            throw new BusinessRuleViolationException("BR-QT-11", "Không thể tự khóa tài khoản của chính mình");
        }
        String reason = rawReason.strip();
        LockTarget target = lockTarget(accountId);
        Account account = target.account();
        if (account.isLocked()) {
            throw new InvalidStateTransitionException("tài khoản", "đã khóa", "khóa");
        }
        Role role = account.getRole();
        AccountStatus status = account.getStatus();
        Instant now = Instant.now(clock);

        List<PendingWorkItem> work;
        List<NotificationRequest> notices;
        if (role == Role.CUSTOMER) {
            CustomerWork customerWork = pendingWork.forCustomer(accountId);
            work = customerWork.items();
            notices = customerNotices(new LockedAccount(customerWork.fullName(), account.getEmail(), role, reason,
                    now), work);
        } else if (target.branchId() != null) {
            work = pendingWork.forBranchStaff(accountId);
            LockedAccount locked = new LockedAccount(target.profile().getFullName(), account.getEmail(), role, reason,
                    now);
            notices = role == Role.BRANCH_MANAGER
                    ? branchManagerNotices(account, target, locked, work)
                    : branchStaffNotices(target.branchId(), locked, work);
        } else {
            // ADMIN, SUPER_MANAGER: không được gán lượt, không mở ca, không có người phụ trách cần báo (docs/adr/0035).
            work = List.of();
            notices = List.of();
        }

        account.lock(reason);
        sessions.revokeAll(accountId);
        events.publishEvent(new AccountLockedEvent(accountId, role, target.branchId(), reason, actor.accountId()));
        notices.forEach(notifications::enqueue);
        audit.record(AuditEntry.of(QtAuditActions.ACCOUNT_LOCKED).entity("accounts", accountId)
                .before(lockSnapshot(false, status)).after(lockSnapshot(true, account.getStatus())).reason(reason));
        accounts.flush();

        log.info("ACCOUNT_LOCKED accountId={} role={} pendingWork={} notices={}", accountId, role, work.size(),
                notices.size());
        return mapper.toLockResult(account, work);
    }

    /**
     * Tài khoản#8 (identity-v1 #30): chỉ gỡ cờ khóa, {@code status} giữ nguyên (BR-QT-12). Không khóa {@code branches}
     * (mở khóa chỉ làm tăng số quản lý đang hoạt động, docs/adr/0029 mục 5), không mở phiên, không thông báo.
     */
    @Transactional
    public AccountSummary unlockAccount(Long accountId, String rawReason) {
        String reason = rawReason.strip();
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("tài khoản", accountId));
        if (!account.isLocked()) {
            throw new InvalidStateTransitionException("tài khoản", "chưa khóa", "mở khóa");
        }

        account.unlock();
        audit.record(AuditEntry.of(QtAuditActions.ACCOUNT_UNLOCKED).entity("accounts", accountId)
                .before(lockSnapshot(true, account.getStatus())).after(lockSnapshot(false, account.getStatus()))
                .reason(reason));
        accounts.flush();

        log.info("ACCOUNT_UNLOCKED accountId={} status={}", accountId, account.getStatus());
        return loginMapper.toAccountSummary(account);
    }

    /**
     * Người bị khóa đã khóa dòng. {@code branchId} chỉ có với A05–A08 (BR-QT-03); {@code lockedBranches} chỉ có khi là
     * BRANCH_MANAGER (để đếm quản lý còn hoạt động dưới khóa, BR-QT-04).
     */
    private record LockTarget(Account account, StaffProfile profile, Long branchId,
                              SortedMap<Long, BranchStatus> lockedBranches) {}

    /**
     * Đọc role bằng projection (không nạp entity) → nếu là BRANCH_MANAGER, khóa {@code branches} của chi nhánh đọc không
     * khóa → {@code findByIdForUpdate} → đọc hồ sơ dưới khóa. Tài khoản biến mất giữa hai bước (ST02 xóa tài khoản
     * {@code PENDING} quá hạn) → 404. Là BRANCH_MANAGER dưới khóa mà chi nhánh hiện tại chưa bị khóa (vừa điều chuyển,
     * vừa đổi chức vụ lên BRANCH_MANAGER) → 409, để việc xác định "quản lý cuối cùng" luôn đếm dưới khóa chi nhánh.
     */
    private LockTarget lockTarget(Long accountId) {
        Role readRole = accounts.findRoleById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("tài khoản", accountId));
        SortedMap<Long, BranchStatus> lockedBranches = Collections.emptySortedMap();
        if (readRole == Role.BRANCH_MANAGER) {
            Long readBranchId = staffProfiles.findStaffRow(accountId).map(StaffRow::branchId).orElse(null);
            if (readBranchId != null) {
                lockedBranches = branchLocks.lockForStaffChange(Set.of(readBranchId));
            }
        }
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("tài khoản", accountId));
        if (account.getRole() == Role.CUSTOMER) {
            return new LockTarget(account, null, null, lockedBranches);
        }
        StaffProfile profile = staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff account " + accountId + " has no staff_profiles"));
        Long branchId = StaffHierarchyPolicy.BRANCH_STAFF.contains(account.getRole())
                ? requireBranch(account, profile) : null;
        if (account.getRole() == Role.BRANCH_MANAGER && !lockedBranches.containsKey(branchId)) {
            throw new ConcurrencyConflictException("tài khoản", accountId);
        }
        return new LockTarget(account, profile, branchId, lockedBranches);
    }

    /** A05–A08 luôn thuộc đúng một chi nhánh (BR-QT-03); thiếu là dữ liệu hỏng → 500, như {@code GET /me}. */
    private static Long requireBranch(Account account, StaffProfile profile) {
        if (profile.getBranchId() == null) {
            throw new IllegalStateException("Branch staff " + account.getId() + " (" + account.getRole()
                    + ") has no branch");
        }
        return profile.getBranchId();
    }

    /**
     * Khách (06 §8 Q2): mỗi chi nhánh có việc dở → mọi lễ tân đang hoạt động của chi nhánh đó, chỉ với việc ở chi nhánh
     * đó. Không có việc dở → không gửi ai. Chi nhánh có việc mà không còn lễ tân hoạt động → log, không gửi (docs/adr/0035).
     */
    private List<NotificationRequest> customerNotices(LockedAccount locked, List<PendingWorkItem> work) {
        Map<Long, List<PendingWorkItem>> byBranch = new TreeMap<>();
        work.forEach(item -> byBranch.computeIfAbsent(item.branchId(), id -> new ArrayList<>()).add(item));
        List<NotificationRequest> notices = new ArrayList<>();
        byBranch.forEach((branchId, items) -> {
            List<Long> receptionists = activeStaffIds(branchId, Role.RECEPTIONIST, null);
            if (receptionists.isEmpty()) {
                log.warn("ACCOUNT_LOCK_NO_RECIPIENT branchId={} items={}", branchId, items.size());
            }
            receptionists.forEach(id -> notices.add(AccountLockNotices.followUp(id, locked, items)));
        });
        return notices;
    }

    /**
     * RECEPTIONIST, VET, CARETAKER: mọi BRANCH_MANAGER đang hoạt động của chi nhánh; chi nhánh không còn quản lý hoạt động
     * thì mọi SUPER_MANAGER (người gán lại lượt đã gọi trong trường hợp đó, BR-TN-08). Luôn gửi, kể cả không có việc dở.
     */
    private List<NotificationRequest> branchStaffNotices(Long branchId, LockedAccount locked,
            List<PendingWorkItem> work) {
        List<Long> recipients = activeStaffIds(branchId, Role.BRANCH_MANAGER, null);
        if (recipients.isEmpty()) {
            recipients = accounts.findActiveIdsByRole(Role.SUPER_MANAGER);
        }
        return recipients.stream().map(id -> AccountLockNotices.followUp(id, locked, work)).toList();
    }

    /**
     * BRANCH_MANAGER → mọi SUPER_MANAGER (BR-QT-11). Là quản lý cuối cùng (đang {@code ACTIVE}, chi nhánh đọc dưới khóa
     * {@code ACTIVE}, không còn BRANCH_MANAGER nào khác đang hoạt động) → chỉ một thông báo
     * {@code LAST_BRANCH_MANAGER_LOCKED_APP} kèm việc dở, thay cho thông báo thường (BR-QT-04; docs/adr/0035).
     */
    private List<NotificationRequest> branchManagerNotices(Account account, LockTarget target, LockedAccount locked,
            List<PendingWorkItem> work) {
        Long branchId = target.branchId();
        boolean last = account.getStatus() == AccountStatus.ACTIVE
                && target.lockedBranches().get(branchId) == BranchStatus.ACTIVE
                && activeStaffIds(branchId, Role.BRANCH_MANAGER, account.getId()).isEmpty();
        List<Long> superManagers = accounts.findActiveIdsByRole(Role.SUPER_MANAGER);
        if (!last) {
            return superManagers.stream().map(id -> AccountLockNotices.followUp(id, locked, work)).toList();
        }
        String branchName = branches.findBranch(branchId)
                .orElseThrow(() -> new IllegalStateException("Branch " + branchId + " vanished under lock"))
                .name();
        return superManagers.stream()
                .map(id -> AccountLockNotices.lastBranchManager(id, locked, branchName, work)).toList();
    }

    /** Nhân viên đang hoạt động ({@code ACTIVE}, không khóa) theo chức vụ tại chi nhánh, bỏ {@code excludeId}. */
    private List<Long> activeStaffIds(Long branchId, Role role, Long excludeId) {
        return staffProfiles.findActiveStaffRows(branchId, role).stream().map(StaffRow::accountId)
                .filter(id -> !id.equals(excludeId)).toList();
    }

    private static Map<String, Object> lockSnapshot(boolean locked, AccountStatus status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("isLocked", locked);
        snapshot.put("status", status.name());
        return snapshot;
    }
}
