package com.petcare.module.identity.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.fsm.AccountTransitionHandler;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Actor;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.extern.slf4j.Slf4j;

/**
 * UC08 — phần ghi của tạo nhân viên và gửi lại mật khẩu ban đầu (identity-v1 #16, #22; docs/adr/0029, 0030). Mỗi
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

    public StaffAccountService(AccountRepository accounts, StaffProfileRepository staffProfiles,
            AccountTransitionHandler transitions, StaffHierarchyPolicy policy, BranchScope branchScope,
            BranchQueryApi branches, SessionService sessions, NotificationApi notifications, AuditRecorder audit,
            StaffAdminMapper mapper, StaffPresence presence) {
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
