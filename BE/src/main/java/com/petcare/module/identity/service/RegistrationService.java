package com.petcare.module.identity.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.RegisterAccountRequest;
import com.petcare.module.identity.dto.RegistrationResponse;
import com.petcare.module.identity.dto.ResendRegistrationOtpRequest;
import com.petcare.module.identity.dto.VerificationResponse;
import com.petcare.module.identity.dto.VerifyAccountRequest;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.fsm.AccountTransitionHandler;
import com.petcare.module.identity.mapper.RegistrationMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.PasswordConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * UC01 — đăng ký tài khoản khách (Tài khoản#1). Một transaction: tài khoản {@code PENDING}, hồ sơ khách online
 * (customer), OTP và email OTP qua outbox (care). Lỗi ở bất kỳ bước nào rollback tất cả.
 * UC02 — xác thực OTP đăng ký (Tài khoản#2), xem {@link #verifyAccount}; gửi lại OTP, xem
 * {@link #resendRegistrationOtp}.
 */
@Slf4j
@Service
public class RegistrationService {

    private final AccountRepository accounts;
    private final AccountTransitionHandler transitions;
    private final CustomerApi customers;
    private final OtpService otps;
    private final NotificationApi notifications;
    private final SystemConfigApi configs;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationMapper mapper;
    private final Clock clock;

    public RegistrationService(AccountRepository accounts, AccountTransitionHandler transitions,
            CustomerApi customers, OtpService otps, NotificationApi notifications, SystemConfigApi configs,
            PasswordEncoder passwordEncoder, RegistrationMapper mapper, Clock clock) {
        this.accounts = accounts;
        this.transitions = transitions;
        this.customers = customers;
        this.otps = otps;
        this.notifications = notifications;
        this.configs = configs;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Tài khoản#1 — đăng ký (BR-TK-01, 02, 03, 04, 05, 07, 08, BR-KH-01). BR-TK-01, 02, 03 chạy trước INSERT đầu
     * tiên; BR-TK-07 (quota OTP) chạy trong {@code issueOtp}, sau INSERT — vi phạm thì rollback cả đăng ký.
     * Hai request cùng email chạy song song: request thua bị {@code uq_accounts_email} chặn → 409 (convention 06).
     * Hạn xác thực {@code pending_expires_at} chốt theo [CFG] lúc đăng ký (BR-TK-08, BR-QT-13; ST02 — docs/adr/0013).
     */
    @Transactional
    public RegistrationResponse registerAccount(RegisterAccountRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);

        if (!Boolean.TRUE.equals(request.isAdult()) || !Boolean.TRUE.equals(request.termsAccepted())) {
            throw new BusinessRuleViolationException("BR-TK-02",
                    "Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng");
        }
        checkPasswordPolicy(request.password());
        if (accounts.existsByEmail(email)) {
            throw new BusinessRuleViolationException("BR-TK-01",
                    "Email đã được sử dụng. Vui lòng đăng nhập hoặc dùng chức năng quên mật khẩu");
        }
        transitions.validateInitial(AccountStatus.PENDING);

        Instant pendingExpiresAt = Instant.now(clock)
                .plus(Duration.ofHours(configs.getInt(ConfigKey.ACCOUNT_PENDING_TTL_HOURS)));
        Account account = accounts.save(Account.registerCustomer(email, passwordEncoder.encode(request.password()),
                pendingExpiresAt));
        String fullName = request.fullName().strip();
        customers.createOnlineProfile(account.getId(), fullName, request.phone());

        IssuedOtp otp = otps.issueOtp(account.getId(), OtpPurpose.REGISTER, email);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_REGISTER, Channel.EMAIL, null,
                email, Map.of("ten_khach", fullName, "ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes()),
                null));

        log.info("ACCOUNT_REGISTERED accountId={}", account.getId());
        return mapper.toResponse(account, otp);
    }

    /**
     * Tài khoản#2 — xác thực OTP đăng ký (BR-TK-05, 06, 19). Không có tài khoản {@code PENDING} với email (chưa đăng
     * ký, đã xác thực, đã bị ST02 xóa) → 404. Mã bị từ chối → {@link OtpRejectedException}, transaction vẫn commit
     * bộ đếm sai (docs/adr/0010); lỗi khác sau khi dùng mã rollback cả {@code consumed_at}. Khóa dòng tài khoản trước
     * khi đọc mã, nên không mất lần đếm sai khi request chạy song song (docs/adr/0011).
     */
    @Transactional(noRollbackFor = OtpRejectedException.class)
    public VerificationResponse verifyAccount(VerifyAccountRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        Account account = lockPendingAccount(email);

        otps.consumeOtp(account.getId(), OtpPurpose.REGISTER, email, request.code());
        transitions.validateTransition(account.getStatus(), AccountStatus.ACTIVE);
        account.verify();
        boolean linkDecisionPending = customers.flagLinkDecisionIfPhoneMatches(account.getId());

        log.info("ACCOUNT_VERIFIED accountId={}", account.getId());
        return mapper.toVerificationResponse(account, linkDecisionPending);
    }

    /**
     * UC02 — gửi lại OTP đăng ký (BR-TK-04, 05, 07). Không đổi trạng thái tài khoản, không kéo dài hạn
     * {@code PENDING} (BR-TK-08). Không có tài khoản {@code PENDING} với email → 404, kiểm trước quota. BR-TK-07 vi
     * phạm thì rollback, mã cũ còn hiệu lực. Payload không có {@code ten_khach}: họ tên nằm ở hồ sơ khách, biến không
     * bắt buộc (BR-QT-14).
     */
    @Transactional
    public OtpSentResponse resendRegistrationOtp(ResendRegistrationOtpRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        Account account = lockPendingAccount(email);

        IssuedOtp otp = otps.issueOtp(account.getId(), OtpPurpose.REGISTER, email);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_REGISTER, Channel.EMAIL, null,
                email, Map.of("ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes()), null));

        log.info("REGISTRATION_OTP_RESENT accountId={}", account.getId());
        return mapper.toOtpSentResponse(otp);
    }

    /** Tài khoản {@code PENDING} của email, đã khóa dòng (docs/adr/0011); không có → 404. */
    private Account lockPendingAccount(String email) {
        return accounts.findByEmailForUpdate(email)
                .filter(found -> found.getStatus() == AccountStatus.PENDING)
                .orElseThrow(() -> new ResourceNotFoundException("tài khoản chờ xác thực", email));
    }

    /** BR-TK-03: tối thiểu {@code password.min_length} [CFG] ký tự, có cả chữ và số; tối đa 72 byte (docs/adr/0009). */
    private void checkPasswordPolicy(String password) {
        int minLength = configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH);
        if (password.codePointCount(0, password.length()) < minLength) {
            throw new BusinessRuleViolationException("BR-TK-03",
                    "Mật khẩu phải có ít nhất " + minLength + " ký tự");
        }
        if (password.codePoints().noneMatch(Character::isLetter)
                || password.codePoints().noneMatch(Character::isDigit)) {
            throw new BusinessRuleViolationException("BR-TK-03", "Mật khẩu phải có cả chữ và số");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordConfig.BCRYPT_MAX_BYTES) {
            throw new BusinessRuleViolationException("BR-TK-03",
                    "Mật khẩu quá dài (tối đa " + PasswordConfig.BCRYPT_MAX_BYTES + " byte)");
        }
    }
}
