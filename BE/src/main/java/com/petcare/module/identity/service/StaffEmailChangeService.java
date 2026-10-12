package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Actor;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import lombok.extern.slf4j.Slf4j;

/**
 * UC08 — sửa hộ email nhân viên (BR-TK-01, 04…07, 16, BR-QT-01; identity-v1 #23, #24; docs/adr/0034). Phần đọc / ghi;
 * chỉ được gọi từ {@link StaffAdminService} (facade không transaction, nơi sinh / so BCrypt). Mã OTP gắn
 * {@code (account_id = nhân viên, target_email = email mới)}, mỗi nhân viên tối đa một mã còn sống
 * ({@link OtpPurpose#invalidatesPerAccount()}). Thứ tự khóa {@code accounts → otp_tokens}; không khóa {@code branches}
 * (đổi email không đổi số BRANCH_MANAGER). Không kiểm trạng thái tài khoản, không hủy phiên (docs/adr/0034 Q1, Q2).
 * Không bắt exception nào bên trong: lỗi đi qua proxy {@code MANDATORY} đã đánh dấu rollback-only.
 */
@Slf4j
@Service
public class StaffEmailChangeService {

    static final String MSG_EMAIL_TAKEN = "Email đã được sử dụng";
    static final String MSG_SAME_EMAIL = "Email mới trùng email hiện tại";
    static final String NOT_FOUND_TYPE = "nhân viên";

    /** Mã đổi email còn mở, đọc không khóa để so BCrypt ngoài transaction. Email và hash không vào log. */
    public record EmailChangeCode(Long otpId, String newEmail, String codeHash) {

        @Override
        public String toString() {
            return "EmailChangeCode[otpId=" + otpId + ", newEmail=***, codeHash=***]";
        }
    }

    /** Kết quả so mã ngoài transaction, để {@link #applyEmailChange} ghi dưới khóa. */
    public record EmailChangeAttempt(Long otpId, String newEmail, boolean matched) {

        @Override
        public String toString() {
            return "EmailChangeAttempt[otpId=" + otpId + ", matched=" + matched + ", newEmail=***]";
        }
    }

    private final AccountRepository accounts;
    private final StaffProfileRepository staffProfiles;
    private final StaffHierarchyPolicy policy;
    private final BranchScope branchScope;
    private final OtpService otps;
    private final NotificationApi notifications;
    private final AuditRecorder audit;
    private final StaffAdminMapper mapper;
    private final StaffPresence presence;
    private final Clock clock;

    public StaffEmailChangeService(AccountRepository accounts, StaffProfileRepository staffProfiles,
            StaffHierarchyPolicy policy, BranchScope branchScope, OtpService otps, NotificationApi notifications,
            AuditRecorder audit, StaffAdminMapper mapper, StaffPresence presence, Clock clock) {
        this.accounts = accounts;
        this.staffProfiles = staffProfiles;
        this.policy = policy;
        this.branchScope = branchScope;
        this.otps = otps;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
        this.presence = presence;
        this.clock = clock;
    }

    /**
     * identity-v1 #23. Mã đã sinh và băm ở {@link OtpService#prepare} (ngoài transaction). Thứ tự: khóa {@code accounts}
     * (404) → phân cấp (403, facade ghi audit sau rollback) → trùng email hiện tại / tài khoản khác (BR-TK-01) →
     * {@link OtpService#issuePrepared} (BR-TK-07 trước mọi lệnh ghi; hủy mã đổi email cũ của nhân viên) → outbox
     * {@code OTP_EMAIL_CHANGE} tới email mới (BR-TK-04). Mọi lỗi rollback: không mã, không email, không tiêu quota.
     */
    @Transactional
    public OtpSentResponse issueEmailChangeOtp(Long accountId, String requestedEmail, PreparedOtp prepared) {
        Account account = lockStaff(accountId);
        checkPolicy(account, readProfile(accountId));
        String newEmail = requestedEmail.strip().toLowerCase(Locale.ROOT);
        if (newEmail.equals(account.getEmail())) {
            throw new BusinessRuleViolationException("BR-TK-01", MSG_SAME_EMAIL);
        }
        if (accounts.existsByEmail(newEmail)) {
            throw new BusinessRuleViolationException("BR-TK-01", MSG_EMAIL_TAKEN);
        }

        IssuedOtp otp = otps.issuePrepared(accountId, OtpPurpose.CHANGE_EMAIL, newEmail, prepared);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_EMAIL_CHANGE, Channel.EMAIL, null,
                newEmail, Map.of("ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes()), null));
        log.info("STAFF_EMAIL_CHANGE_OTP_SENT accountId={}", accountId);
        return mapper.toOtpSentResponse(otp);
    }

    /**
     * identity-v1 #24, bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi so BCrypt. Không có /
     * tài khoản khách → 404; phân cấp kiểm <b>trước</b> khi tra mã (403), để người ngoài phân cấp không biết nhân viên có
     * mã đang mở hay không. Rỗng khi không còn mã — facade trả BR-TK-05 sau khi transaction này kết thúc.
     */
    @Transactional(readOnly = true)
    public Optional<EmailChangeCode> findEmailChangeCode(Long accountId) {
        StaffRow row = staffProfiles.findStaffRow(accountId)
                .filter(found -> found.role() != Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, accountId));
        policy.check(Actor.of(branchScope.current()), StaffHierarchyPolicy.Request.on(
                StaffAction.CHANGE_EMAIL, accountId, row.role(), row.branchId()));
        return otps.findActiveForAccount(accountId, OtpPurpose.CHANGE_EMAIL)
                .map(otp -> new EmailChangeCode(otp.id(), otp.targetEmail(), otp.codeHash()));
    }

    /**
     * identity-v1 #24, ghi dưới khóa dòng {@code accounts} (BR-TK-01, 05, 06, 16; docs/adr/0025 checklist).
     * <ol>
     *   <li>Khóa dòng; phân cấp kiểm lại dưới khóa (role / chi nhánh có thể vừa đổi) → 403, chưa đụng mã.</li>
     *   <li>{@link OtpService#settleCheckedOtp} là lệnh ghi <b>đầu tiên</b>: sai mã → bộ đếm được commit nhờ
     *       {@code noRollbackFor} (docs/adr/0010); mã mới hơn / hết hạn → BR-TK-05 không đếm.</li>
     *   <li>BR-TK-01 dưới khóa: email vừa bị dùng → rollback cả {@code consumed_at}, mã dùng lại được.</li>
     *   <li>Đổi email, thông báo tới email cũ và mới, audit — cùng transaction; {@code flush()} để vi phạm
     *       {@code uq_accounts_email} (hai xác nhận song song) nổ thành 409 tại đây, trước khi đọc lại.</li>
     * </ol>
     */
    @Transactional(noRollbackFor = OtpRejectedException.class)
    public StaffResponse applyEmailChange(Long accountId, EmailChangeAttempt attempt) {
        Account account = lockStaff(accountId);
        checkPolicy(account, readProfile(accountId));
        otps.settleCheckedOtp(accountId, OtpPurpose.CHANGE_EMAIL, attempt.newEmail(), attempt.otpId(),
                attempt.matched());
        if (accounts.existsByEmail(attempt.newEmail())) {
            throw new BusinessRuleViolationException("BR-TK-01", MSG_EMAIL_TAKEN);
        }

        String oldEmail = account.getEmail();
        account.changeEmail(attempt.newEmail());
        String changedAt = LoginAttemptService.UNLOCK_TIME_FORMAT.format(Instant.now(clock));
        Map<String, Object> payload = Map.of("email_cu", oldEmail, "email_moi", attempt.newEmail(),
                "thoi_diem", changedAt);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE, Channel.EMAIL,
                null, oldEmail, payload, null));
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE, Channel.EMAIL,
                null, attempt.newEmail(), payload, null));
        audit.record(AuditEntry.of(QtAuditActions.STAFF_EMAIL_CHANGED).entity("accounts", accountId)
                .before(emailSnapshot(oldEmail, false)).after(emailSnapshot(attempt.newEmail(), true)));
        accounts.flush();

        log.info("STAFF_EMAIL_CHANGED accountId={}", accountId);
        StaffDetailRow row = staffProfiles.findStaffDetail(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff " + accountId + " not readable"));
        return mapper.toResponse(row, StaffPresence.isOnline(row.lastSeenAt(), presence.onlineSince()));
    }

    /** Khóa dòng {@code accounts} trước mọi lệnh đọc khác (docs/adr/0029 mục 4); không có / khách → 404. */
    private Account lockStaff(Long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .filter(found -> found.getRole() != Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, accountId));
    }

    private StaffProfile readProfile(Long accountId) {
        return staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Staff account " + accountId + " has no staff_profiles"));
    }

    /** BR-QT-01, BR-TK-15, 16: role / chi nhánh của người bị tác động đọc dưới khóa dòng {@code accounts}. */
    private void checkPolicy(Account account, StaffProfile profile) {
        policy.check(Actor.of(branchScope.current()), StaffHierarchyPolicy.Request.on(
                StaffAction.CHANGE_EMAIL, account.getId(), account.getRole(), profile.getBranchId()));
    }

    private static Map<String, Object> emailSnapshot(String email, boolean withMethod) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("email", email);
        if (withMethod) {
            snapshot.put("verificationMethod", IdentityAuditActions.VERIFICATION_EMAIL_CODE);
        }
        return snapshot;
    }
}
