package com.petcare.module.identity.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.fsm.AccountTransitionHandler;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Actor;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.extern.slf4j.Slf4j;

/**
 * UC08 — phần ghi của tạo nhân viên, gửi lại mật khẩu ban đầu, đổi chức vụ, điều chuyển (identity-v1 #16, #22, #18,
 * #19; docs/adr/0029, 0030, 0031). Mỗi
 * method một transaction trọn use case; chỉ được gọi từ {@link StaffAdminService} (facade không transaction, nơi đã
 * sinh mật khẩu tạm và BCrypt ngoài transaction). Thứ tự kiểm theo docs/adr/0029 mục 3: tồn tại (404) → phân cấp (403,
 * audit do facade ghi sau rollback) → BR (400) → trạng thái (409); mọi lỗi ném trước lệnh ghi đầu tiên. Không bắt
 * exception nào bên trong: lỗi đi qua proxy {@code MANDATORY}/{@code REQUIRED} đã đánh dấu rollback-only.
 */
@Slf4j
@Service
public class StaffAccountService {

    private final AccountRepository accounts;
    private final StaffProfileRepository staffProfiles;
    private final AccountTransitionHandler transitions;
    private final StaffHierarchyPolicy policy;
    private final BranchScope branchScope;
    private final BranchQueryApi branches;
    private final SessionService sessions;
    private final NotificationApi notifications;
    private final AuditRecorder audit;
    private final StaffAdminMapper mapper;
    private final StaffPresence presence;
    private final BranchApi branchLocks;
    private final VisitQueryApi visitQueries;
    private final CashierShiftQueryApi cashierShifts;

    public StaffAccountService(AccountRepository accounts, StaffProfileRepository staffProfiles,
            AccountTransitionHandler transitions, StaffHierarchyPolicy policy, BranchScope branchScope,
            BranchQueryApi branches, SessionService sessions, NotificationApi notifications, AuditRecorder audit,
            StaffAdminMapper mapper, StaffPresence presence, BranchApi branchLocks, VisitQueryApi visitQueries,
            CashierShiftQueryApi cashierShifts) {
        this.accounts = accounts;
        this.staffProfiles = staffProfiles;
        this.transitions = transitions;
        this.policy = policy;
        this.branchScope = branchScope;
        this.branches = branches;
        this.sessions = sessions;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
        this.presence = presence;
        this.branchLocks = branchLocks;
        this.visitQueries = visitQueries;
        this.cashierShifts = cashierShifts;
    }

    /**
     * Tài khoản#4 — tạo tài khoản nhân viên (BR-QT-01, 02, 03, BR-TK-01, 17). Không khóa dòng có sẵn nào: tạo chỉ làm
     * tăng số BRANCH_MANAGER (docs/adr/0029 mục 5); hai request trùng email song song bị {@code uq_accounts_email} chặn
     * → 409. Tài khoản, hồ sơ, email mật khẩu (outbox) và audit cùng commit hoặc cùng rollback.
     */
    @Transactional
    public StaffResponse createStaff(CreateStaffRequest request, TemporaryPassword password) {
        Actor actor = Actor.of(branchScope.current());
        Role role = Role.valueOf(request.role());

        policy.check(actor, StaffHierarchyPolicy.Request.create(role, request.branchId()));
        Long branchId = effectiveBranch(actor, role, request.branchId());
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (accounts.existsByEmail(email)) {
            throw new BusinessRuleViolationException("BR-TK-01", "Email đã được sử dụng");
        }
        transitions.validateInitial(AccountStatus.ACTIVE);

        String fullName = request.fullName().strip();
        Account account = accounts.save(Account.createStaff(email, request.phone(), password.passwordHash(), role));
        staffProfiles.save(StaffProfile.create(account.getId(), fullName, branchId));
        sendTemporaryPassword(email, fullName, password);
        audit.record(AuditEntry.of(QtAuditActions.STAFF_CREATED).entity("accounts", account.getId())
                .after(createdSnapshot(account, fullName, branchId)));
        staffProfiles.flush();

        log.info("STAFF_CREATED accountId={} role={} branchId={}", account.getId(), role, branchId);
        return toResponse(staffProfiles.findStaffDetail(account.getId())
                .orElseThrow(() -> new IllegalStateException("Staff " + account.getId() + " not readable after insert")));
    }

    /**
     * UC08 — gửi lại mật khẩu ban đầu (BR-QT-02; docs/adr/0030). Khóa dòng {@code accounts} trước mọi lệnh đọc khác
     * (docs/adr/0029 mục 4), nên role / chi nhánh / cờ đổi mật khẩu dùng để quyết định không đổi được tới lúc commit.
     * Chỉ khi {@code ACTIVE} và chưa đổi mật khẩu lần đầu ({@code is_locked} vẫn được); hủy mọi phiên (thứ tự
     * {@code accounts → sessions}, docs/adr/0021).
     */
    @Transactional
    public void resendInitialPassword(Long accountId, TemporaryPassword password) {
        Account account = accounts.findByIdForUpdate(accountId)
                .filter(found -> found.getRole() != Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", accountId));
        StaffProfile profile = staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff account " + accountId + " has no staff_profiles"));

        policy.check(Actor.of(branchScope.current()), StaffHierarchyPolicy.Request.on(
                StaffAction.RESEND_INITIAL_PASSWORD, accountId, account.getRole(), profile.getBranchId()));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidStateTransitionException("tài khoản nhân viên", account.getStatus().name(),
                    "gửi lại mật khẩu ban đầu");
        }
        if (!account.isMustChangePassword()) {
            throw new InvalidStateTransitionException("tài khoản nhân viên", "đã đổi mật khẩu lần đầu",
                    "gửi lại mật khẩu ban đầu");
        }

        account.reissueInitialPassword(password.passwordHash());
        sessions.revokeAll(accountId);
        sendTemporaryPassword(account.getEmail(), profile.getFullName(), password);
        audit.record(AuditEntry.of(QtAuditActions.STAFF_INITIAL_PASSWORD_RESENT).entity("accounts", accountId));

        log.info("STAFF_INITIAL_PASSWORD_RESENT accountId={}", accountId);
    }

    /**
     * UC08 — đổi chức vụ (BR-QT-04, 05; docs/adr/0031). Khóa {@code branches} (chi nhánh hiện tại) → {@code accounts}
     * → đọc {@code staff_profiles} (docs/adr/0029 mục 4), rồi: phân cấp (403, audit do facade) → trùng chức vụ hiện tại
     * thì trả nguyên trạng, không ghi → chi nhánh vừa đổi trong lúc chờ khóa → 409 → BR-QT-04 (đếm dưới khóa chi nhánh,
     * nợ D014). Không kiểm BR-QT-08 (spec #18 không có — docs/adr/0031 TBD), không hủy phiên (role đọc lại mỗi
     * request). VET đổi sang chức vụ khác thì xóa {@code specialty}, {@code bio}.
     */
    @Transactional
    public StaffResponse changeStaffRole(Long accountId, ChangeRoleRequest request) {
        Role newRole = Role.valueOf(request.role());
        LockedStaff target = lockTarget(accountId, null);
        Account account = target.account();
        StaffProfile profile = target.profile();
        Role oldRole = account.getRole();

        policy.check(Actor.of(branchScope.current()), StaffHierarchyPolicy.Request.changeRole(
                accountId, oldRole, profile.getBranchId(), newRole));
        Long branchId = requireBranch(account, profile);
        if (oldRole == newRole) {
            return currentResponse(accountId);
        }
        if (target.branchMoved()) {
            throw new ConcurrencyConflictException("nhân viên", accountId);
        }
        checkNotLastBranchManager(account, branchId, target.lockedStatus(branchId), "Không đổi chức vụ được");

        Map<String, Object> before = roleSnapshot(oldRole, profile);
        account.changeStaffRole(newRole);
        if (oldRole == Role.VET) {
            profile.clearVetProfile();
        }
        audit.record(AuditEntry.of(QtAuditActions.STAFF_ROLE_CHANGED).entity("accounts", accountId)
                .before(before).after(roleSnapshot(newRole, profile)));
        staffProfiles.flush();

        log.info("STAFF_ROLE_CHANGED accountId={} from={} to={}", accountId, oldRole, newRole);
        return currentResponse(accountId);
    }

    /**
     * UC08 — điều chuyển nhân viên (BR-QT-03, 04, 06, 08; docs/adr/0031). Khóa {@code branches} nguồn + đích (một lần
     * gọi, id tăng dần) → {@code accounts} → đọc {@code staff_profiles}, rồi: phân cấp (403) → đã ở chi nhánh đích thì
     * trả nguyên trạng → chi nhánh vừa đổi trong lúc chờ khóa → 409 → BR-QT-03 → BR-QT-04 trên chi nhánh nguồn →
     * BR-QT-08 (cuối cùng: hai interface còn là placeholder, nợ D016). Không hủy phiên (chi nhánh đọc lại mỗi request).
     */
    @Transactional
    public StaffResponse transferStaff(Long accountId, TransferStaffRequest request) {
        Long newBranchId = request.branchId();
        LockedStaff target = lockTarget(accountId, newBranchId);
        Account account = target.account();
        StaffProfile profile = target.profile();

        policy.check(Actor.of(branchScope.current()), StaffHierarchyPolicy.Request.transfer(
                accountId, account.getRole(), profile.getBranchId(), newBranchId));
        Long branchId = requireBranch(account, profile);
        if (branchId.equals(newBranchId)) {
            return currentResponse(accountId);
        }
        if (target.branchMoved()) {
            throw new ConcurrencyConflictException("nhân viên", accountId);
        }
        if (!target.lockedBranches().containsKey(newBranchId)) {
            throw new BusinessRuleViolationException("BR-QT-03", "Chi nhánh không tồn tại");
        }
        checkNotLastBranchManager(account, branchId, target.lockedStatus(branchId), "Không điều chuyển được");
        checkNoPendingWork(accountId);

        profile.transferTo(newBranchId);
        audit.record(AuditEntry.of(QtAuditActions.STAFF_TRANSFERRED).entity("accounts", accountId)
                .before(branchSnapshot(branchId)).after(branchSnapshot(newBranchId)));
        staffProfiles.flush();

        log.info("STAFF_TRANSFERRED accountId={} from={} to={}", accountId, branchId, newBranchId);
        return currentResponse(accountId);
    }

    /** Người bị tác động đã khóa: entity đọc sau khóa, các chi nhánh đã khóa, chi nhánh có đổi so với lần đọc trước. */
    private record LockedStaff(Account account, StaffProfile profile, SortedMap<Long, BranchStatus> lockedBranches,
                               boolean branchMoved) {

        BranchStatus lockedStatus(Long branchId) {
            BranchStatus status = lockedBranches.get(branchId);
            if (status == null) {
                throw new IllegalStateException("Branch " + branchId + " of staff " + account.getId() + " not locked");
            }
            return status;
        }
    }

    /**
     * Thứ tự khóa của quản trị nhân viên (docs/adr/0029 mục 4): đọc {@code branch_id} bằng projection không khóa (không
     * có / tài khoản khách → 404) → {@code BranchApi.lockForStaffChange} (chi nhánh hiện tại + {@code extraBranchId}, id
     * tăng dần) → {@code findByIdForUpdate(accounts)} → đọc {@code staff_profiles} sau khóa (ghi dưới khóa
     * {@code accounts} như {@code PATCH /me/staff-profile}, docs/adr/0026). {@code branchMoved}: chi nhánh đọc dưới khóa
     * khác lần đọc không khóa, tức chi nhánh đã khóa không còn là chi nhánh hiện tại.
     */
    private LockedStaff lockTarget(Long accountId, Long extraBranchId) {
        StaffRow row = staffProfiles.findStaffRow(accountId)
                .filter(found -> found.role() != Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", accountId));
        Set<Long> branchIds = new HashSet<>();
        if (row.branchId() != null) {
            branchIds.add(row.branchId());
        }
        if (extraBranchId != null) {
            branchIds.add(extraBranchId);
        }
        SortedMap<Long, BranchStatus> locked = branchLocks.lockForStaffChange(branchIds);
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff account " + accountId + " vanished under lock"));
        StaffProfile profile = staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff account " + accountId + " has no staff_profiles"));
        return new LockedStaff(account, profile, locked, !Objects.equals(row.branchId(), profile.getBranchId()));
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
     * BR-QT-04: chi nhánh {@code ACTIVE} phải còn ít nhất 1 BRANCH_MANAGER {@code ACTIVE} (người bị khóa vẫn tính,
     * docs/adr/0024). Chỉ thao tác làm giảm số đó mới bị chặn: người bị tác động là BRANCH_MANAGER {@code ACTIVE} và chi
     * nhánh (đọc dưới khóa) đang {@code ACTIVE}. Đếm sau khi khóa {@code branches}, như {@code activateBranch} (D014).
     */
    private void checkNotLastBranchManager(Account account, Long branchId, BranchStatus branchStatus, String action) {
        if (account.getRole() == Role.BRANCH_MANAGER && account.getStatus() == AccountStatus.ACTIVE
                && branchStatus == BranchStatus.ACTIVE && staffProfiles.countActiveBranchManagers(branchId) <= 1) {
            throw new BusinessRuleViolationException("BR-QT-04",
                    action + ": đây là quản lý cuối cùng của chi nhánh đang hoạt động");
        }
    }

    /**
     * BR-QT-08 (BR-QT-06 "phải thỏa BR-QT-08 trước khi điều chuyển"): không còn lượt tiếp nhận được gán chưa kết thúc,
     * không còn ca thu ngân {@code OPEN}. Hỏi cả hai với mọi chức vụ (đổi chức vụ không chặn việc dở, docs/adr/0031) và
     * liệt kê đủ việc dở. Hai interface còn là placeholder ném lỗi (nợ D016).
     */
    private void checkNoPendingWork(Long accountId) {
        List<Long> visitIds = visitQueries.findUnfinishedVisitIdsAssignedTo(accountId);
        boolean openShift = cashierShifts.hasOpenShift(accountId);
        List<String> pending = new ArrayList<>();
        if (!visitIds.isEmpty()) {
            pending.add("còn lượt tiếp nhận " + visitIds.stream().map(id -> "#" + id)
                    .collect(Collectors.joining(", ")) + " chưa hoàn tất");
        }
        if (openShift) {
            pending.add("còn ca thu ngân đang mở");
        }
        if (!pending.isEmpty()) {
            throw new BusinessRuleViolationException("BR-QT-08",
                    "Không điều chuyển được: " + String.join("; ", pending));
        }
    }

    private StaffResponse currentResponse(Long accountId) {
        return toResponse(staffProfiles.findStaffDetail(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff " + accountId + " not readable")));
    }

    private static Map<String, Object> roleSnapshot(Role role, StaffProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("role", role.name());
        snapshot.put("specialty", profile.getSpecialty());
        snapshot.put("bio", profile.getBio());
        return snapshot;
    }

    private static Map<String, Object> branchSnapshot(Long branchId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("branchId", branchId);
        return snapshot;
    }

    /**
     * BR-QT-03, kiểm sau phân cấp (docs/adr/0029 mục 3) để người không có quyền không dò được chi nhánh. SUPER_MANAGER
     * không gắn chi nhánh; A05–A08 đúng một chi nhánh, BRANCH_MANAGER để trống = chi nhánh của mình (00-method §3.3).
     * Tầng 1 chi nhánh chỉ có {@code DRAFT}/{@code ACTIVE} và không bị xóa, nên có dòng là hợp lệ, không cần khóa.
     */
    private Long effectiveBranch(Actor actor, Role role, Long requestedBranchId) {
        if (role == Role.SUPER_MANAGER) {
            if (requestedBranchId != null) {
                throw new BusinessRuleViolationException("BR-QT-03", "Quản lý cấp chuỗi không thuộc chi nhánh nào");
            }
            return null;
        }
        Long branchId = requestedBranchId != null || actor.role() != Role.BRANCH_MANAGER
                ? requestedBranchId : actor.branchId();
        if (branchId == null) {
            throw new BusinessRuleViolationException("BR-QT-03", "Vui lòng chọn chi nhánh cho nhân viên");
        }
        if (branches.findBranch(branchId).isEmpty()) {
            throw new BusinessRuleViolationException("BR-QT-03", "Chi nhánh không tồn tại");
        }
        return branchId;
    }

    /**
     * BR-QT-02: mật khẩu gốc chỉ vào payload outbox (khóa {@code mat_khau_tam}, bị xóa sau khi gửi), cùng transaction
     * với hash — không có email nào mang mật khẩu chưa được lưu. Đủ biến bắt buộc của mẫu V11.
     */
    private void sendTemporaryPassword(String email, String fullName, TemporaryPassword password) {
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.STAFF_TEMP_PASSWORD, Channel.EMAIL,
                null, email, Map.of("ho_ten", fullName, "email_dang_nhap", email,
                        "mat_khau_tam", password.plainPassword()), null));
    }

    private static Map<String, Object> createdSnapshot(Account account, String fullName, Long branchId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("email", account.getEmail());
        after.put("phone", account.getPhone());
        after.put("fullName", fullName);
        after.put("role", account.getRole().name());
        after.put("branchId", branchId);
        after.put("status", account.getStatus().name());
        return after;
    }

    private StaffResponse toResponse(StaffDetailRow row) {
        return mapper.toResponse(row, StaffPresence.isOnline(row.lastSeenAt(), presence.onlineSince()));
    }
}
