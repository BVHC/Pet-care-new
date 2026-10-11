package com.petcare.module.identity.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.CustomerQueryApi.LinkCandidate;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.mapper.LinkProfileMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Phần có transaction của UC07 — liên kết tài khoản với hồ sơ khách có sẵn (BR-TK-19; docs/adr/0025, docs/adr/0027).
 * {@link LinkProfileService} (không transaction) chạy BCrypt khi không giữ connection hay khóa dòng; các method ở đây chỉ
 * đọc, khóa và ghi trong vài ms.
 * <ul>
 *   <li>Mọi method ghi khóa dòng {@code accounts} của chính tài khoản trước mọi lời gọi sang customer: thứ tự khóa
 *       {@code accounts → otp_tokens → customers}, cùng chiều mọi luồng khác (docs/adr/0011, 0021, 0025 mục 8).</li>
 *   <li>Thứ tự kiểm: quyền (BR-TK-11) → tồn tại (404) → guard BR-TK-19 → BR-TK-07 (docs/api/00-method.md §3.5). Riêng
 *       {@link #applyLink}: mã OTP được xử lý <b>trước</b> mọi kiểm / ghi khác để bộ đếm sai luôn được commit
 *       (docs/adr/0025 checklist 3).</li>
 *   <li>Không bắt exception bên trong transaction (docs/adr/0025 checklist 7).</li>
 * </ul>
 */
@Slf4j
@Service
public class LinkProfileAttemptService {

    static final String MSG_ALREADY_LINKED = "Tài khoản đã được liên kết với hồ sơ khách hàng tại phòng khám";
    static final String MSG_HAS_DATA = "Hồ sơ của bạn đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không"
            + " thể liên kết với hồ sơ tại quầy. Vui lòng liên hệ quầy để được hỗ trợ";
    static final String MSG_NO_EMAIL = "Hồ sơ tại quầy chưa có email nên không thể liên kết online."
            + " Vui lòng ra quầy để được hỗ trợ";
    static final String NOT_FOUND_TYPE = "hồ sơ khách";

    /** Mã liên kết còn mở, đọc không khóa để so BCrypt ngoài transaction. {@code codeHash} không xuất hiện trong log. */
    public record LinkCode(Long customerId, String counterEmail, Long otpId, String codeHash) {

        @Override
        public String toString() {
            return "LinkCode[customerId=" + customerId + ", otpId=" + otpId + ", counterEmail=***, codeHash=***]";
        }
    }

    /** Kết quả so mã ngoài transaction, để {@link #applyLink} ghi dưới khóa. */
    public record LinkAttempt(Long customerId, String counterEmail, Long otpId, boolean matched) {

        @Override
        public String toString() {
            return "LinkAttempt[customerId=" + customerId + ", otpId=" + otpId + ", matched=" + matched
                    + ", counterEmail=***]";
        }
    }

    /** Snapshot audit của {@code CUSTOMER_PROFILE_LINKED} (record, không phải entity — convention 08). */
    record LinkSnapshot(Long counterCustomerId, Long onlineCustomerId, Long linkedAccountId,
            String verificationMethod) {
    }

    private final AccountRepository accounts;
    private final OtpService otps;
    private final CustomerQueryApi customerQueries;
    private final CustomerApi customers;
    private final NotificationApi notifications;
    private final AuditRecorder audit;
    private final LinkProfileMapper mapper;

    public LinkProfileAttemptService(AccountRepository accounts, OtpService otps, CustomerQueryApi customerQueries,
            CustomerApi customers, NotificationApi notifications, AuditRecorder audit, LinkProfileMapper mapper) {
        this.accounts = accounts;
        this.otps = otps;
        this.customerQueries = customerQueries;
        this.customers = customers;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
    }

    /**
     * identity-v1 #11. Đọc không khóa: tài khoản phải còn liên kết được (BR-TK-19), rồi liệt kê hồ sơ tại quầy chưa liên
     * kết có SĐT trùng {@code phone} (bỏ trống = SĐT hồ sơ online; hồ sơ không có SĐT → danh sách rỗng).
     */
    @Transactional(readOnly = true)
    public List<LinkCandidateResponse> listCandidates(Long accountId, String phone) {
        requireLinkable(accountId);
        String lookupPhone = resolvePhone(accountId, phone);
        if (lookupPhone == null) {
            return List.of();
        }
        return mapper.toCandidates(candidates(lookupPhone));
    }

    /**
     * identity-v1 #12. Mã đã sinh và băm ở {@link OtpService#prepare} (ngoài transaction). Thứ tự: khóa tài khoản →
     * BR-TK-11 → hồ sơ phải là ứng viên của SĐT (không thì 404, không phân biệt với hồ sơ không tồn tại) → BR-TK-19
     * (tài khoản còn liên kết được; hồ sơ tại quầy có email) → {@link OtpService#issuePrepared} (BR-TK-07, hủy mã liên kết
     * cũ của tài khoản) → outbox {@code OTP_PROFILE_LINK} tới email hồ sơ tại quầy (BR-TK-04). Mọi lỗi rollback: không
     * mã, không email, không tiêu quota.
     */
    @Transactional
    public OtpSentResponse issueLinkOtp(Long accountId, Long customerId, String phone, PreparedOtp prepared) {
        Account account = lockActive(accountId);
        String lookupPhone = resolvePhone(accountId, phone);
        if (lookupPhone == null
                || candidates(lookupPhone).stream().noneMatch(candidate -> customerId.equals(candidate.customerId()))) {
            throw new ResourceNotFoundException(NOT_FOUND_TYPE, customerId);
        }
        requireLinkable(accountId);
        String counterEmail = customerQueries.findContact(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, customerId))
                .email();
        if (counterEmail == null || counterEmail.isBlank()) {
            throw new BusinessRuleViolationException("BR-TK-19", MSG_NO_EMAIL);
        }

        IssuedOtp otp = otps.issuePrepared(accountId, OtpPurpose.LINK_PROFILE, counterEmail, customerId, prepared);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_PROFILE_LINK, Channel.EMAIL, null,
                counterEmail, Map.of("ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes(),
                        "email_tai_khoan", account.getEmail()), null));
        log.info("PROFILE_LINK_OTP_SENT accountId={} customerId={}", accountId, customerId);
        return mapper.toOtpSentResponse(otp, EmailMasker.mask(counterEmail));
    }

    /**
     * identity-v1 #13, bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi so BCrypt. Hồ sơ không tồn
     * tại → 404. Rỗng khi hồ sơ không có email hoặc tài khoản không có mã liên kết còn mở cho hồ sơ này — caller trả
     * BR-TK-05.
     */
    @Transactional(readOnly = true)
    public Optional<LinkCode> findLinkCode(Long accountId, Long customerId) {
        CustomerContact counter = customerQueries.findContact(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, customerId));
        String counterEmail = counter.email();
        if (counterEmail == null || counterEmail.isBlank()) {
            return Optional.empty();
        }
        return otps.findActive(accountId, OtpPurpose.LINK_PROFILE, counterEmail, customerId)
                .map(otp -> new LinkCode(customerId, counterEmail, otp.id(), otp.codeHash()));
    }

    /**
     * identity-v1 #13, ghi dưới khóa dòng {@code accounts} (BR-TK-05, 06, 19).
     * <ol>
     *   <li>Khóa dòng; tài khoản vừa bị khóa / vô hiệu hóa → BR-TK-11, trước mọi lệnh ghi.</li>
     *   <li>{@link OtpService#settleCheckedOtp} là lệnh ghi <b>đầu tiên</b>: mã sai → bộ đếm được commit nhờ
     *       {@code noRollbackFor} (docs/adr/0010); mã mới hơn / hết hạn → BR-TK-05 không đếm.</li>
     *   <li>{@link CustomerApi#linkAccountToCounterProfile} kiểm lại dưới khóa {@code customers}; BR-TK-19 → rollback cả
     *       {@code consumed_at}, mã dùng lại được (docs/adr/0025 checklist 3).</li>
     *   <li>Audit {@code CUSTOMER_PROFILE_LINKED} trong cùng transaction: không có liên kết nào thiếu audit.</li>
     * </ol>
     */
    @Transactional(noRollbackFor = OtpRejectedException.class)
    public LinkResult applyLink(Long accountId, LinkAttempt attempt) {
        Account account = lockActive(accountId);
        otps.settleCheckedOtp(accountId, OtpPurpose.LINK_PROFILE, attempt.counterEmail(), attempt.customerId(),
                attempt.otpId(), attempt.matched());

        Long onlineCustomerId = ownProfileId(accountId);
        customers.linkAccountToCounterProfile(accountId, attempt.customerId(), attempt.counterEmail(),
                account.getEmail());
        audit.record(AuditEntry.of(IdentityAuditActions.CUSTOMER_PROFILE_LINKED)
                .entity("customers", attempt.customerId())
                .before(new LinkSnapshot(attempt.customerId(), onlineCustomerId, null, null))
                .after(new LinkSnapshot(attempt.customerId(), null, accountId,
                        IdentityAuditActions.VERIFICATION_EMAIL_CODE)));
        log.info("PROFILE_LINKED accountId={} customerId={}", accountId, attempt.customerId());
        return mapper.toLinkResult(attempt.customerId());
    }

    /**
     * identity-v1 #14 "Không phải tôi" (BR-TK-19 (b)). Dưới khóa dòng {@code accounts} nên không chạy chen với
     * {@link #applyLink} của cùng tài khoản; hồ sơ không còn cờ → 409 (cờ là thuộc tính của hồ sơ khách, không có bảng
     * chuyển trạng thái ở 03 nên không có {@code TransitionHandler} — docs/adr/0027).
     */
    @Transactional
    public void decline(Long accountId) {
        lockActive(accountId);
        Long ownProfileId = ownProfileId(accountId);
        boolean pending = customerQueries.findContact(ownProfileId)
                .orElseThrow(() -> new IllegalStateException("Customer " + ownProfileId + " not found"))
                .linkDecisionPending();
        if (!pending) {
            throw new InvalidStateTransitionException("liên kết hồ sơ", "không còn chờ quyết định", "Không phải tôi");
        }
        customers.declineLink(accountId);
        log.info("PROFILE_LINK_DECLINED accountId={}", accountId);
    }

    /** Khóa dòng tài khoản; BR-TK-11 kiểm lại dưới khóa (như {@code StaffProfileService}, docs/adr/0026). */
    private Account lockActive(Long accountId) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account " + accountId + " not found"));
        if (account.isLocked() || account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-TK-11", LoginAttemptService.MSG_LOCKED);
        }
        return account;
    }

    private void requireLinkable(Long accountId) {
        OnlineProfileLinkability linkability = Objects.requireNonNull(
                customerQueries.checkOnlineProfileLinkable(accountId), "checkOnlineProfileLinkable returned null");
        switch (linkability) {
            case LINKABLE -> {
                // còn liên kết được
            }
            case ALREADY_LINKED -> throw new BusinessRuleViolationException("BR-TK-19", MSG_ALREADY_LINKED);
            case HAS_DATA -> throw new BusinessRuleViolationException("BR-TK-19", MSG_HAS_DATA);
        }
    }

    /** {@code phone} đã gửi lên, hoặc SĐT của hồ sơ khách gắn tài khoản (có thể {@code null}). */
    private String resolvePhone(Long accountId, String phone) {
        if (phone != null) {
            return phone;
        }
        Long ownProfileId = ownProfileId(accountId);
        return customerQueries.findContact(ownProfileId)
                .orElseThrow(() -> new IllegalStateException("Customer " + ownProfileId + " not found"))
                .phone();
    }

    /** BR-KH-01: tài khoản khách luôn có hồ sơ; không có là dữ liệu sai (500, như {@code MeService}). */
    private Long ownProfileId(Long accountId) {
        return customerQueries.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Customer account " + accountId + " has no profile"));
    }

    private List<LinkCandidate> candidates(String phone) {
        return Objects.requireNonNull(customerQueries.findLinkCandidates(phone), "findLinkCandidates returned null");
    }
}
