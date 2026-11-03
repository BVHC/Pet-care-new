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
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.VerificationMethod;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.CustomerAccountNotEditableException;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * UC22 — lễ tân sửa hộ email tài khoản của khách tại quầy (BR-TK-01, 04…07, 16; identity-v1 #25, #26). Phần đọc / ghi;
 * chỉ được gọi từ {@link CustomerAccountService} (facade không transaction, nơi sinh / so BCrypt). Khuôn
 * {@link StaffEmailChangeService} (docs/adr/0034): mã OTP gắn {@code (account_id = tài khoản của hồ sơ, target_email =
 * email mới)}, không gắn {@code customer_id} (chỉ {@code LINK_PROFILE} mới có), mỗi tài khoản tối đa một mã còn sống.
 * <p>
 * Khác nhân viên: đường vào là <b>hồ sơ khách</b> ({@link CustomerQueryApi#findContact}, đọc không khóa) rồi mới khóa
 * dòng {@code accounts} của tài khoản gắn với hồ sơ; không có phân cấp BR-QT-01 (hồ sơ khách dùng chung toàn chuỗi, chỉ
 * RECEPTIONIST gọi được — kiểm ở controller). Thứ tự kiểm: hồ sơ không có (404) → hồ sơ chưa gắn tài khoản (400 không
 * mã BR) → khóa {@code accounts} → không phải tài khoản khách (404, lễ tân không sửa email nhân viên qua cửa này) →
 * {@code PENDING} (400 không mã BR; {@link Account#changeEmail} sẽ ném lỗi lập trình) → BR-TK-01. Tài khoản đang bị khóa
 * ({@code is_locked}) vẫn sửa được: đây là đường khôi phục tài khoản (BR-TK-16). Thứ tự khóa {@code accounts →
 * otp_tokens}; không ghi {@code customers} — đồng bộ {@code customers.email} là việc của module customer. Không bắt
 * exception nào bên trong: lỗi đi qua proxy {@code MANDATORY} đã đánh dấu rollback-only.
 */
@Slf4j
@Service
public class CustomerEmailChangeService {

    static final String NOT_FOUND_TYPE = "hồ sơ khách";
    static final String MSG_NOT_LINKED = "Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa";
    static final String MSG_ACCOUNT_PENDING = "Tài khoản chưa xác thực email nên chưa sửa được email";

    private final CustomerQueryApi customers;
    private final AccountRepository accounts;
    private final OtpService otps;
    private final NotificationApi notifications;
    private final AuditRecorder audit;
    private final StaffAdminMapper mapper;
    private final Clock clock;

    public CustomerEmailChangeService(CustomerQueryApi customers, AccountRepository accounts, OtpService otps,
            NotificationApi notifications, AuditRecorder audit, StaffAdminMapper mapper, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.otps = otps;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * identity-v1 #25. Mã đã sinh và băm ở {@link OtpService#prepare} (ngoài transaction). Thứ tự: hồ sơ → khóa tài khoản
     * → trùng email hiện tại / tài khoản khác (BR-TK-01) → {@link OtpService#issuePrepared} (BR-TK-07 trước mọi lệnh ghi;
     * hủy mã đổi email cũ của tài khoản) → outbox {@code OTP_EMAIL_CHANGE} tới email mới (BR-TK-04). Mọi lỗi rollback:
     * không mã, không email, không tiêu quota.
     */
    @Transactional
    public OtpSentResponse issueEmailChangeOtp(Long customerId, String requestedEmail, PreparedOtp prepared) {
        Account account = lockCustomerAccount(customerId);
        String newEmail = requestedEmail.strip().toLowerCase(Locale.ROOT);
        if (newEmail.equals(account.getEmail())) {
            throw new BusinessRuleViolationException("BR-TK-01", StaffEmailChangeService.MSG_SAME_EMAIL);
        }
        if (accounts.existsByEmail(newEmail)) {
            throw new BusinessRuleViolationException("BR-TK-01", StaffEmailChangeService.MSG_EMAIL_TAKEN);
        }

        IssuedOtp otp = otps.issuePrepared(account.getId(), OtpPurpose.CHANGE_EMAIL, newEmail, prepared);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_EMAIL_CHANGE, Channel.EMAIL, null,
                newEmail, Map.of("ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes()), null));
        log.info("CUSTOMER_EMAIL_CHANGE_OTP_SENT customerId={} accountId={}", customerId, account.getId());
        return mapper.toOtpSentResponse(otp);
    }

    /**
     * identity-v1 #26, bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi so BCrypt. Cùng 404 / 400
     * với #25 (hồ sơ không có, chưa gắn tài khoản, không phải tài khoản khách, {@code PENDING}) — kiểm trước khi tra mã.
     * Rỗng khi không còn mã — facade trả BR-TK-05 sau khi transaction này kết thúc.
     */
    @Transactional(readOnly = true)
    public Optional<EmailChangeCode> findEmailChangeCode(Long customerId) {
        Long accountId = linkedAccountId(customerId);
        requireEditable(accounts.findById(accountId), customerId);
        return otps.findActiveForAccount(accountId, OtpPurpose.CHANGE_EMAIL)
                .map(otp -> new EmailChangeCode(otp.id(), otp.targetEmail(), otp.codeHash()));
    }

    /**
     * identity-v1 #26, ghi dưới khóa dòng {@code accounts} (BR-TK-01, 05, 06, 16; docs/adr/0025 checklist).
     * <ol>
     *   <li>Khóa dòng, kiểm lại hồ sơ / tài khoản dưới khóa (đều chưa có lệnh ghi nào, rollback an toàn).</li>
     *   <li>{@link OtpService#settleCheckedOtp} là lệnh ghi <b>đầu tiên</b>: sai mã → bộ đếm được commit nhờ
     *       {@code noRollbackFor} (docs/adr/0010); mã mới hơn / hết hạn → BR-TK-05 không đếm.</li>
     *   <li>BR-TK-01 dưới khóa: email vừa bị dùng → rollback cả {@code consumed_at}, mã dùng lại được.</li>
     *   <li>Đổi email, thông báo tới email cũ và mới, audit — cùng transaction; {@code flush()} để vi phạm
     *       {@code uq_accounts_email} (hai xác nhận song song) nổ thành 409 tại đây.</li>
     * </ol>
     * Không hủy phiên của khách, không đổi trạng thái / khóa / mật khẩu (docs/adr/0034 Q1, Q2).
     */
    @Transactional(noRollbackFor = OtpRejectedException.class)
    public void applyEmailChange(Long customerId, EmailChangeAttempt attempt) {
        Account account = lockCustomerAccount(customerId);
        otps.settleCheckedOtp(account.getId(), OtpPurpose.CHANGE_EMAIL, attempt.newEmail(), attempt.otpId(),
                attempt.matched());
        if (accounts.existsByEmail(attempt.newEmail())) {
            throw new BusinessRuleViolationException("BR-TK-01", StaffEmailChangeService.MSG_EMAIL_TAKEN);
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
        audit.record(AuditEntry.of(IdentityAuditActions.CUSTOMER_EMAIL_CHANGED).entity("accounts", account.getId())
                .before(emailSnapshot(oldEmail, false)).after(emailSnapshot(attempt.newEmail(), true)));
        accounts.flush();

        log.info("CUSTOMER_EMAIL_CHANGED customerId={} accountId={}", customerId, account.getId());
    }

    /** Hồ sơ → tài khoản gắn với nó, đọc không khóa: không có hồ sơ → 404; chưa gắn tài khoản → 400 không mã BR. */
    private Long linkedAccountId(Long customerId) {
        CustomerContact contact = customers.findContact(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, customerId));
        if (contact.accountId() == null) {
            throw new CustomerAccountNotEditableException(MSG_NOT_LINKED);
        }
        return contact.accountId();
    }

    /** Khóa dòng {@code accounts} trước mọi lệnh đọc khác của use case (docs/adr/0029 mục 4, docs/adr/0011). */
    private Account lockCustomerAccount(Long customerId) {
        Long accountId = linkedAccountId(customerId);
        return requireEditable(accounts.findByIdForUpdate(accountId), customerId);
    }

    /**
     * Tài khoản phải là tài khoản khách: nhân viên → 404 như hồ sơ không tồn tại (dữ liệu hỏng, nhưng lễ tân không được
     * sửa email nhân viên qua cửa này — đó là việc của cấp trên, BR-QT-01). {@code PENDING} chưa xác thực email → 400.
     */
    private static Account requireEditable(Optional<Account> found, Long customerId) {
        Account account = found.filter(candidate -> candidate.getRole() == Role.CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, customerId));
        if (account.getStatus() == AccountStatus.PENDING) {
            throw new CustomerAccountNotEditableException(MSG_ACCOUNT_PENDING);
        }
        return account;
    }

    /** Snapshot audit: record / Map, không phải entity; không có khóa nào chứa mã OTP, mật khẩu hay hash. */
    private static Map<String, Object> emailSnapshot(String email, boolean withMethod) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("email", email);
        if (withMethod) {
            snapshot.put("verificationMethod", VerificationMethod.ID_CARD_IN_PERSON.name());
        }
        return snapshot;
    }
}
