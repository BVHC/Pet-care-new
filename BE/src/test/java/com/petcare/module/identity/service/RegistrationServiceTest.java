package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
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
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC01, Tài khoản#1: BR-TK-01, 02, 03, 04, 07, 08, BR-KH-01; thứ tự guard trước mọi lần ghi.
 * UC02, Tài khoản#2: chỉ tài khoản {@code PENDING}, OTP trước chuyển trạng thái, hệ quả BR-TK-19 sau cùng.
 * UC02, gửi lại OTP: khóa tài khoản → 404 trước quota → mã mới → outbox không có {@code ten_khach}.
 */
class RegistrationServiceTest {

    private static final long ACCOUNT_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-06T03:05:00Z");
    private static final Instant RESEND = Instant.parse("2026-10-06T03:01:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final CustomerApi customers = mock(CustomerApi.class);
    private final OtpService otps = mock(OtpService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final RegistrationService service = new RegistrationService(accounts, new AccountTransitionHandler(),
            customers, otps, notifications, configs, encoder, new PasswordPolicy(configs),
            Mappers.getMapper(RegistrationMapper.class),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(8);
        when(configs.getInt(ConfigKey.ACCOUNT_PENDING_TTL_HOURS)).thenReturn(24);
        when(accounts.existsByEmail(anyString())).thenReturn(false);
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
            return account;
        });
        when(otps.issueOtp(eq(ACCOUNT_ID), eq(OtpPurpose.REGISTER), anyString()))
                .thenReturn(new IssuedOtp("123456", EXPIRES, RESEND, 5));
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void registersPendingCustomerThenProfileThenOtpThenOutboxInOrder() {
        RegistrationResponse response = service.registerAccount(request(" Khach@PetCare.Test ", "abc12345",
                " Nguyễn Văn A ", "0901234567", true, true));

        Account saved = savedAccount();
        assertThat(saved.getEmail()).isEqualTo("khach@petcare.test");
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(saved.getPhone()).as("BR-TK-01: tài khoản khách không lưu SĐT").isNull();
        assertThat(saved.isLocked()).isFalse();
        assertThat(saved.isMustChangePassword()).isFalse();
        assertThat(saved.getPasswordHash()).isNotEqualTo("abc12345");
        assertThat(encoder.matches("abc12345", saved.getPasswordHash())).isTrue();
        assertThat(saved.getPendingExpiresAt()).as("BR-TK-08: hạn chốt lúc đăng ký")
                .isEqualTo(NOW.plusSeconds(24 * 3600));

        InOrder order = inOrder(accounts, customers, otps, notifications);
        order.verify(accounts).existsByEmail("khach@petcare.test");
        order.verify(accounts).save(any(Account.class));
        order.verify(customers).createOnlineProfile(ACCOUNT_ID, "Nguyễn Văn A", "0901234567");
        order.verify(otps).issueOtp(ACCOUNT_ID, OtpPurpose.REGISTER, "khach@petcare.test");
        order.verify(notifications).enqueue(any(NotificationRequest.class));

        NotificationRequest sent = sentNotification();
        assertThat(sent.templateCode()).isEqualTo(NotificationTemplateCode.OTP_REGISTER);
        assertThat(sent.channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.recipientEmail()).as("BR-TK-04: gửi tới email vừa nhập").isEqualTo("khach@petcare.test");
        assertThat(sent.recipientAccountId()).isNull();
        assertThat(sent.payload()).containsEntry("ma_otp", "123456").containsEntry("ten_khach", "Nguyễn Văn A")
                .containsEntry("thoi_han_phut", 5);

        assertThat(response).isEqualTo(new RegistrationResponse(ACCOUNT_ID, "khach@petcare.test",
                AccountStatus.PENDING, RESEND));
    }

    /** BR-TK-08, BR-QT-13: hạn {@code PENDING} lấy [CFG] tại thời điểm đăng ký và chốt vào tài khoản. */
    @Test
    void pendingExpiryUsesConfiguredTtlAtRegistration() {
        when(configs.getInt(ConfigKey.ACCOUNT_PENDING_TTL_HOURS)).thenReturn(48);

        service.registerAccount(request("a@petcare.test", "abc12345", "A", null, true, true));

        assertThat(savedAccount().getPendingExpiresAt()).isEqualTo(NOW.plusSeconds(48 * 3600));
    }

    @Test
    void phoneIsOptionalAndPassedAsNull() {
        service.registerAccount(request("a@petcare.test", "abc12345", "A", null, true, true));

        verify(customers).createOnlineProfile(ACCOUNT_ID, "A", null);
    }

    // ---------------------------------------------------------------- BR-TK-02

    @ParameterizedTest
    @CsvSource({"false, true", "true, false", "false, false"})
    void adultAndTermsMustBothBeTicked(boolean isAdult, boolean termsAccepted) {
        assertRejected(request("a@petcare.test", "abc12345", "A", null, isAdult, termsAccepted),
                "Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng (BR-TK-02)");
    }

    // ---------------------------------------------------------------- BR-TK-03

    @Test
    void passwordShorterThanConfiguredMinimumIsRejected() {
        assertRejected(request("a@petcare.test", "abc1234", "A", null, true, true),
                "Mật khẩu phải có ít nhất 8 ký tự (BR-TK-03)");
    }

    @Test
    void passwordExactlyMinimumLengthIsAccepted() {
        service.registerAccount(request("a@petcare.test", "abc12345", "A", null, true, true));

        verify(accounts).save(any(Account.class));
    }

    @Test
    void minimumLengthComesFromConfig() {
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(10);

        assertRejected(request("a@petcare.test", "abc123456", "A", null, true, true),
                "Mật khẩu phải có ít nhất 10 ký tự (BR-TK-03)");
    }

    @Test
    void minimumLengthCountsCharactersNotBytes() {
        // 8 ký tự có dấu (> 8 byte UTF-8) vẫn đủ độ dài
        service.registerAccount(request("a@petcare.test", "mậtkhẩu1", "A", null, true, true));

        verify(accounts).save(any(Account.class));
    }

    @ParameterizedTest
    @CsvSource({"abcdefgh", "12345678"})
    void passwordNeedsBothLettersAndDigits(String password) {
        assertRejected(request("a@petcare.test", password, "A", null, true, true),
                "Mật khẩu phải có cả chữ và số (BR-TK-03)");
    }

    @Test
    void passwordOver72BytesIsRejectedBeforeBcrypt() {
        assertRejected(request("a@petcare.test", "a1" + "x".repeat(71), "A", null, true, true),
                "Mật khẩu quá dài (tối đa 72 byte) (BR-TK-03)");
    }

    @Test
    void passwordOfExactly72BytesIsAccepted() {
        service.registerAccount(request("a@petcare.test", "a1" + "x".repeat(70), "A", null, true, true));

        verify(accounts).save(any(Account.class));
    }

    // ---------------------------------------------------------------- BR-TK-01

    @Test
    void emailAlreadyUsedIsRejectedWithSuggestion() {
        when(accounts.existsByEmail("khach@petcare.test")).thenReturn(true);

        assertRejected(request("KHACH@petcare.test", "abc12345", "A", null, true, true),
                "Email đã được sử dụng. Vui lòng đăng nhập hoặc dùng chức năng quên mật khẩu (BR-TK-01)");
    }

    @Test
    void ruleOrderIsTermsThenPasswordThenEmail() {
        when(accounts.existsByEmail(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.registerAccount(request("a@petcare.test", "short", "A", null, false, true)))
                .hasMessageEndingWith("(BR-TK-02)");
        assertThatThrownBy(() -> service.registerAccount(request("a@petcare.test", "short", "A", null, true, true)))
                .hasMessageEndingWith("(BR-TK-03)");
    }

    // ---------------------------------------------------------------- lỗi ở bước sau đi thẳng lên

    @Test
    void otpQuotaViolationPropagatesSoWholeRegistrationRollsBack() {
        when(otps.issueOtp(eq(ACCOUNT_ID), eq(OtpPurpose.REGISTER), anyString()))
                .thenThrow(new BusinessRuleViolationException("BR-TK-07", "Đã gửi quá nhiều mã OTP"));

        assertThatThrownBy(() -> service.registerAccount(request("a@petcare.test", "abc12345", "A", null, true, true)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-07)");
        verifyNoInteractions(notifications);
    }

    @Test
    void customerApiFailurePropagatesBeforeOtpAndOutbox() {
        when(customers.createOnlineProfile(any(), any(), any())).thenThrow(new UnsupportedOperationException("nợ"));

        assertThatThrownBy(() -> service.registerAccount(request("a@petcare.test", "abc12345", "A", null, true, true)))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(otps, notifications);
    }

    // ---------------------------------------------------------------- UC02, Tài khoản#2 — verifyAccount

    @Test
    void verifyConsumesOtpThenActivatesThenFlagsLinkDecision() {
        Account account = pendingAccount("khach@petcare.test");
        when(customers.flagLinkDecisionIfPhoneMatches(ACCOUNT_ID)).thenReturn(true);

        VerificationResponse response = service.verifyAccount(new VerifyAccountRequest(" Khach@PetCare.Test ", "123456"));

        InOrder order = inOrder(accounts, otps, customers);
        order.verify(accounts).findByEmailForUpdate("khach@petcare.test");
        order.verify(otps).consumeOtp(ACCOUNT_ID, OtpPurpose.REGISTER, "khach@petcare.test", "123456");
        order.verify(customers).flagLinkDecisionIfPhoneMatches(ACCOUNT_ID);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPendingExpiresAt()).as("hết PENDING thì bỏ hạn (ck_accounts_pending_expiry)").isNull();
        assertThat(response).isEqualTo(new VerificationResponse(ACCOUNT_ID, AccountStatus.ACTIVE, true));
        verifyNoInteractions(notifications);
    }

    @Test
    void verifyReturnsLinkDecisionPendingFalseWhenNoCounterProfileMatches() {
        pendingAccount("khach@petcare.test");
        when(customers.flagLinkDecisionIfPhoneMatches(ACCOUNT_ID)).thenReturn(false);

        assertThat(service.verifyAccount(new VerifyAccountRequest("khach@petcare.test", "123456")).linkDecisionPending())
                .isFalse();
    }

    @Test
    void verifyUnknownEmailIsNotFound() {
        when(accounts.findByEmailForUpdate("khach@petcare.test")).thenReturn(Optional.empty());

        assertVerifyNotFound("khach@petcare.test");
    }

    @Test
    void verifyAlreadyActiveAccountIsNotFound() {
        Account account = pendingAccount("khach@petcare.test");
        account.verify();

        assertVerifyNotFound("khach@petcare.test");
    }

    @Test
    void rejectedOtpStopsBeforeActivationAndLinkFlag() {
        Account account = pendingAccount("khach@petcare.test");
        doThrow(new OtpRejectedException("BR-TK-05", "OTP không đúng hoặc đã hết hạn"))
                .when(otps).consumeOtp(ACCOUNT_ID, OtpPurpose.REGISTER, "khach@petcare.test", "000000");

        assertThatThrownBy(() -> service.verifyAccount(new VerifyAccountRequest("khach@petcare.test", "000000")))
                .isExactlyInstanceOf(OtpRejectedException.class).hasMessageEndingWith("(BR-TK-05)");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING);
        verifyNoInteractions(customers);
    }

    @Test
    void customerApiFailureAfterActivationPropagatesSoEverythingRollsBack() {
        pendingAccount("khach@petcare.test");
        when(customers.flagLinkDecisionIfPhoneMatches(ACCOUNT_ID)).thenThrow(new UnsupportedOperationException("nợ"));

        assertThatThrownBy(() -> service.verifyAccount(new VerifyAccountRequest("khach@petcare.test", "123456")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- UC02 — resendRegistrationOtp

    @Test
    void resendLocksAccountThenIssuesOtpThenEnqueuesWithoutCustomerName() {
        Account account = pendingAccount("khach@petcare.test");

        OtpSentResponse response = service.resendRegistrationOtp(
                new ResendRegistrationOtpRequest(" Khach@PetCare.Test "));

        InOrder order = inOrder(accounts, otps, notifications);
        order.verify(accounts).findByEmailForUpdate("khach@petcare.test");
        order.verify(otps).issueOtp(ACCOUNT_ID, OtpPurpose.REGISTER, "khach@petcare.test");
        order.verify(notifications).enqueue(any(NotificationRequest.class));

        NotificationRequest sent = sentNotification();
        assertThat(sent.templateCode()).isEqualTo(NotificationTemplateCode.OTP_REGISTER);
        assertThat(sent.channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.recipientEmail()).as("BR-TK-04: gửi tới email đăng ký").isEqualTo("khach@petcare.test");
        assertThat(sent.recipientAccountId()).isNull();
        assertThat(sent.payload()).as("BR-QT-14: đủ biến bắt buộc, không có ten_khach")
                .containsOnlyKeys("ma_otp", "thoi_han_phut")
                .containsEntry("ma_otp", "123456").containsEntry("thoi_han_phut", 5);
        assertThat(sent.linkUrl()).isNull();

        assertThat(response).isEqualTo(new OtpSentResponse(RESEND, null));
        assertThat(account.getStatus()).as("resend không chuyển trạng thái").isEqualTo(AccountStatus.PENDING);
        verifyNoInteractions(customers);
    }

    @Test
    void resendUnknownEmailIsNotFound() {
        when(accounts.findByEmailForUpdate("khach@petcare.test")).thenReturn(Optional.empty());

        assertResendNotFound("khach@petcare.test");
    }

    @Test
    void resendForActiveAccountIsNotFound() {
        pendingAccount("khach@petcare.test").verify();

        assertResendNotFound("khach@petcare.test");
    }

    @Test
    void resendForDisabledAccountIsNotFound() {
        ReflectionTestUtils.setField(pendingAccount("khach@petcare.test"), "status", AccountStatus.DISABLED);

        assertResendNotFound("khach@petcare.test");
    }

    @Test
    void resendQuotaViolationPropagatesAndNothingIsEnqueued() {
        pendingAccount("khach@petcare.test");
        doThrow(new BusinessRuleViolationException("BR-TK-07", "Vui lòng chờ 30 giây trước khi gửi lại mã OTP"))
                .when(otps).issueOtp(ACCOUNT_ID, OtpPurpose.REGISTER, "khach@petcare.test");

        assertThatThrownBy(() -> service.resendRegistrationOtp(new ResendRegistrationOtpRequest("khach@petcare.test")))
                .isExactlyInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-07)");
        verifyNoInteractions(notifications, customers);
    }

    private void assertResendNotFound(String email) {
        assertThatThrownBy(() -> service.resendRegistrationOtp(new ResendRegistrationOtpRequest(email)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản chờ xác thực #" + email);
        verifyNoInteractions(otps, notifications, customers);
    }

    private Account pendingAccount(String email) {
        Account account = Account.registerCustomer(email, "hash", NOW.plusSeconds(24 * 3600));
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        when(accounts.findByEmailForUpdate(email)).thenReturn(Optional.of(account));
        return account;
    }

    private void assertVerifyNotFound(String email) {
        assertThatThrownBy(() -> service.verifyAccount(new VerifyAccountRequest(email, "123456")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy tài khoản chờ xác thực #" + email);
        verifyNoInteractions(otps, customers);
    }

    private void assertRejected(RegisterAccountRequest request, String message) {
        assertThatThrownBy(() -> service.registerAccount(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(message);
        verify(accounts, org.mockito.Mockito.never()).save(any());
        verifyNoInteractions(customers, otps, notifications);
    }

    private Account savedAccount() {
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(captor.capture());
        return captor.getValue();
    }

    private NotificationRequest sentNotification() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(captor.capture());
        return captor.getValue();
    }

    private static RegisterAccountRequest request(String email, String password, String fullName, String phone,
            boolean isAdult, boolean termsAccepted) {
        return new RegisterAccountRequest(email, password, fullName, phone, isAdult, termsAccepted);
    }
}
