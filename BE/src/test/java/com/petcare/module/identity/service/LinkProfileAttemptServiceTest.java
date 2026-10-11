package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.CustomerQueryApi.LinkCandidate;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.mapper.LinkProfileMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkAttempt;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkCode;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkSnapshot;
import com.petcare.module.identity.service.OtpService.ActiveOtp;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Phần có transaction của UC07 (BR-TK-04…07, 11, 19; docs/adr/0025, 0027): thứ tự kiểm quyền → tồn tại → guard, khóa
 * {@code accounts} trước mọi lời gọi customer, settle OTP là lệnh ghi đầu tiên, mọi nhánh lỗi không gọi lệnh ghi nào
 * phía sau. Mapper thật.
 */
class LinkProfileAttemptServiceTest {

    private static final long ACCOUNT = 9L;
    private static final long ONLINE = 50L;
    private static final long COUNTER = 70L;
    private static final String ACCOUNT_EMAIL = "khach@petcare.test";
    private static final String COUNTER_EMAIL = "nguyen.van.a@gmail.com";
    private static final String OWN_PHONE = "0901234567";
    private static final PreparedOtp PREPARED = new PreparedOtp("123456", "code-hash");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OtpService otps = mock(OtpService.class);
    private final CustomerQueryApi customerQueries = mock(CustomerQueryApi.class);
    private final CustomerApi customers = mock(CustomerApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final LinkProfileMapper mapper = Mappers.getMapper(LinkProfileMapper.class);
    private final LinkProfileAttemptService service = new LinkProfileAttemptService(accounts, otps, customerQueries,
            customers, notifications, audit, mapper);

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.registerCustomer(ACCOUNT_EMAIL, "x", null);
        ReflectionTestUtils.setField(account, "id", ACCOUNT);
        ReflectionTestUtils.setField(account, "role", Role.CUSTOMER);
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
        when(customerQueries.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(ONLINE));
        when(customerQueries.findContact(ONLINE)).thenReturn(Optional.of(
                new CustomerContact(ONLINE, "Khách online", OWN_PHONE, ACCOUNT_EMAIL, ACCOUNT, true)));
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(
                new CustomerContact(COUNTER, "Nguyễn Văn A", OWN_PHONE, COUNTER_EMAIL, null, false)));
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.LINKABLE);
        when(customerQueries.findLinkCandidates(OWN_PHONE))
                .thenReturn(List.of(new LinkCandidate(COUNTER, "Ng*** V** A", true)));
    }

    // ---------------------------------------------------------------- #11 danh sách ứng viên

    @Test
    void candidatesDefaultToTheOnlineProfilePhone() {
        assertThat(service.listCandidates(ACCOUNT, null))
                .containsExactly(new LinkCandidateResponse(COUNTER, "Ng*** V** A", true));
    }

    @Test
    void candidatesUseTheTypedPhoneWhenGiven() {
        when(customerQueries.findLinkCandidates("0987654321"))
                .thenReturn(List.of(new LinkCandidate(71L, "Tr*** B", false)));

        assertThat(service.listCandidates(ACCOUNT, "0987654321"))
                .containsExactly(new LinkCandidateResponse(71L, "Tr*** B", false));
        verify(customerQueries, never()).findCustomerIdByAccountId(anyLong());
    }

    @Test
    void onlineProfileWithoutPhoneHasNoCandidates() {
        when(customerQueries.findContact(ONLINE)).thenReturn(Optional.of(
                new CustomerContact(ONLINE, "Khách online", null, ACCOUNT_EMAIL, ACCOUNT, false)));

        assertThat(service.listCandidates(ACCOUNT, null)).isEmpty();
        verify(customerQueries, never()).findLinkCandidates(any());
    }

    @Test
    void candidatesRefusedWhenOnlineProfileHasData() {
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.HAS_DATA);

        assertRule(() -> service.listCandidates(ACCOUNT, null), "BR-TK-19",
                LinkProfileAttemptService.MSG_HAS_DATA);
        verify(customerQueries, never()).findLinkCandidates(any());
    }

    @Test
    void candidatesRefusedWhenAccountAlreadyLinked() {
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.ALREADY_LINKED);

        assertRule(() -> service.listCandidates(ACCOUNT, null), "BR-TK-19",
                LinkProfileAttemptService.MSG_ALREADY_LINKED);
    }

    @Test
    void nullCandidateListIsAProgrammingError() {
        when(customerQueries.findLinkCandidates(OWN_PHONE)).thenReturn(null);

        assertThatThrownBy(() -> service.listCandidates(ACCOUNT, null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------- #12 gửi mã

    @Test
    void otpGoesToCounterProfileEmailAfterAllChecksInOrder() {
        Instant resendAt = Instant.parse("2026-10-10T03:01:00Z");
        when(otps.issuePrepared(ACCOUNT, OtpPurpose.LINK_PROFILE, COUNTER_EMAIL, COUNTER, PREPARED))
                .thenReturn(new IssuedOtp("123456", Instant.parse("2026-10-10T03:05:00Z"), resendAt, 5));

        OtpSentResponse response = service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED);

        assertThat(response).isEqualTo(new OtpSentResponse(resendAt, "ng***@gmail.com"));
        InOrder order = inOrder(accounts, customerQueries, otps, notifications);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(customerQueries).findLinkCandidates(OWN_PHONE);
        order.verify(customerQueries).checkOnlineProfileLinkable(ACCOUNT);
        order.verify(customerQueries).findContact(COUNTER);
        order.verify(otps).issuePrepared(ACCOUNT, OtpPurpose.LINK_PROFILE, COUNTER_EMAIL, COUNTER, PREPARED);
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.OTP_PROFILE_LINK,
                Channel.EMAIL, null, COUNTER_EMAIL,
                Map.of("ma_otp", "123456", "thoi_han_phut", 5, "email_tai_khoan", ACCOUNT_EMAIL), null));
        verifyNoInteractions(customers, audit);
    }

    @Test
    void otpRefusedForLockedOrDisabledAccountBeforeAnyCustomerCall() {
        ReflectionTestUtils.setField(account, "locked", true);
        assertRule(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED), "BR-TK-11",
                LoginAttemptService.MSG_LOCKED);

        ReflectionTestUtils.setField(account, "locked", false);
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);
        assertRule(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED), "BR-TK-11",
                LoginAttemptService.MSG_LOCKED);

        verifyNoInteractions(customerQueries, otps, notifications);
    }

    @Test
    void nonCandidateIs404AndBeatsTheDataGuard() {
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.HAS_DATA);

        assertThatThrownBy(() -> service.issueLinkOtp(ACCOUNT, 999L, null, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerQueries, never()).checkOnlineProfileLinkable(anyLong());
        verifyNoInteractions(otps, notifications);
    }

    @Test
    void typedPhoneDecidesTheCandidates() {
        when(customerQueries.findLinkCandidates("0987654321")).thenReturn(List.of());

        assertThatThrownBy(() -> service.issueLinkOtp(ACCOUNT, COUNTER, "0987654321", PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(otps, notifications);
    }

    @Test
    void onlineProfileWithoutPhoneAndNoTypedPhoneIs404() {
        when(customerQueries.findContact(ONLINE)).thenReturn(Optional.of(
                new CustomerContact(ONLINE, "Khách online", null, ACCOUNT_EMAIL, ACCOUNT, false)));

        assertThatThrownBy(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerQueries, never()).findLinkCandidates(any());
        verifyNoInteractions(otps, notifications);
    }

    @Test
    void otpRefusedWhenAccountCannotLinkAnymore() {
        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.HAS_DATA);
        assertRule(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED), "BR-TK-19",
                LinkProfileAttemptService.MSG_HAS_DATA);

        when(customerQueries.checkOnlineProfileLinkable(ACCOUNT)).thenReturn(OnlineProfileLinkability.ALREADY_LINKED);
        assertRule(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED), "BR-TK-19",
                LinkProfileAttemptService.MSG_ALREADY_LINKED);

        verifyNoInteractions(otps, notifications);
    }

    @Test
    void counterProfileWithoutEmailSendsNothing() {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(
                new CustomerContact(COUNTER, "Nguyễn Văn A", OWN_PHONE, null, null, false)));

        assertRule(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED), "BR-TK-19",
                LinkProfileAttemptService.MSG_NO_EMAIL);
        verifyNoInteractions(otps, notifications);
    }

    @Test
    void quotaViolationPropagatesWithoutEnqueue() {
        when(otps.issuePrepared(ACCOUNT, OtpPurpose.LINK_PROFILE, COUNTER_EMAIL, COUNTER, PREPARED))
                .thenThrow(new BusinessRuleViolationException("BR-TK-07", "Vui lòng chờ 30 giây"));

        assertThatThrownBy(() -> service.issueLinkOtp(ACCOUNT, COUNTER, null, PREPARED))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-07)");
        verifyNoInteractions(notifications);
    }

    // ---------------------------------------------------------------- #13 đọc mã

    @Test
    void openCodeIsReadByAccountProfileAndProfileEmail() {
        when(otps.findActive(ACCOUNT, OtpPurpose.LINK_PROFILE, COUNTER_EMAIL, COUNTER))
                .thenReturn(Optional.of(new ActiveOtp(501L, "code-hash", Instant.parse("2026-10-10T03:05:00Z"))));

        assertThat(service.findLinkCode(ACCOUNT, COUNTER))
                .contains(new LinkCode(COUNTER, COUNTER_EMAIL, 501L, "code-hash"));
    }

    @Test
    void unknownProfileIs404AtConfirm() {
        when(customerQueries.findContact(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLinkCode(ACCOUNT, 999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).errorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(otps);
    }

    @Test
    void profileWithoutEmailHasNoCode() {
        when(customerQueries.findContact(COUNTER)).thenReturn(Optional.of(
                new CustomerContact(COUNTER, "Nguyễn Văn A", OWN_PHONE, null, null, false)));

        assertThat(service.findLinkCode(ACCOUNT, COUNTER)).isEmpty();
        verifyNoInteractions(otps);
    }

    @Test
    void linkCodeToStringHidesHashAndEmail() {
        assertThat(new LinkCode(COUNTER, COUNTER_EMAIL, 501L, "code-hash").toString())
                .doesNotContain("code-hash").doesNotContain(COUNTER_EMAIL);
        assertThat(new LinkAttempt(COUNTER, COUNTER_EMAIL, 501L, true).toString()).doesNotContain(COUNTER_EMAIL);
    }

    // ---------------------------------------------------------------- #13 ghi dưới khóa

    @Test
    void applyLinkLocksSettlesLinksThenAudits() {
        LinkResult result = service.applyLink(ACCOUNT, new LinkAttempt(COUNTER, COUNTER_EMAIL, 501L, true));

        assertThat(result).isEqualTo(new LinkResult(COUNTER));
        InOrder order = inOrder(accounts, otps, customerQueries, customers, audit);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(otps).settleCheckedOtp(ACCOUNT, OtpPurpose.LINK_PROFILE, COUNTER_EMAIL, COUNTER, 501L, true);
        order.verify(customerQueries).findCustomerIdByAccountId(ACCOUNT);
        order.verify(customers).linkAccountToCounterProfile(ACCOUNT, COUNTER, COUNTER_EMAIL, ACCOUNT_EMAIL);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(IdentityAuditActions.CUSTOMER_PROFILE_LINKED);
        assertThat(entry.getValue().entityType()).isEqualTo("customers");
        assertThat(entry.getValue().entityId()).isEqualTo(COUNTER);
        assertThat(entry.getValue().before()).isEqualTo(new LinkSnapshot(COUNTER, ONLINE, null, null));
        assertThat(entry.getValue().after()).isEqualTo(
                new LinkSnapshot(COUNTER, null, ACCOUNT, IdentityAuditActions.VERIFICATION_EMAIL_CODE));
        assertThat(entry.getValue().actorOverridden()).as("actor = chủ token").isFalse();
    }

    @Test
    void applyLinkRefusesLockedAccountBeforeSettling() {
        ReflectionTestUtils.setField(account, "locked", true);

        assertRule(() -> service.applyLink(ACCOUNT, new LinkAttempt(COUNTER, COUNTER_EMAIL, 501L, true)),
                "BR-TK-11", LoginAttemptService.MSG_LOCKED);
        verifyNoInteractions(otps, customers, audit);
    }

    @Test
    void wrongCodeNeverReachesCustomerOrAudit() {
        doThrow(new OtpRejectedException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE)).when(otps)
                .settleCheckedOtp(anyLong(), any(), anyString(), anyLong(), anyLong(), anyBoolean());

        assertThatThrownBy(() -> service.applyLink(ACCOUNT, new LinkAttempt(COUNTER, COUNTER_EMAIL, 501L, false)))
                .isInstanceOf(OtpRejectedException.class);
        verifyNoInteractions(customers, audit);
    }

    @Test
    void customerRejectionUnderLockSkipsAudit() {
        doThrow(new BusinessRuleViolationException("BR-TK-19", "Hồ sơ đã được liên kết")).when(customers)
                .linkAccountToCounterProfile(ACCOUNT, COUNTER, COUNTER_EMAIL, ACCOUNT_EMAIL);

        assertThatThrownBy(() -> service.applyLink(ACCOUNT, new LinkAttempt(COUNTER, COUNTER_EMAIL, 501L, true)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .isNotInstanceOf(OtpRejectedException.class).hasMessageEndingWith("(BR-TK-19)");
        verifyNoInteractions(audit);
    }

    // ---------------------------------------------------------------- #14 "Không phải tôi"

    @Test
    void declineClearsTheFlagUnderTheAccountLock() {
        service.decline(ACCOUNT);

        InOrder order = inOrder(accounts, customerQueries, customers);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(customerQueries).findContact(ONLINE);
        order.verify(customers).declineLink(ACCOUNT);
        verifyNoInteractions(audit);
    }

    @Test
    void declineWithoutFlagIs409() {
        when(customerQueries.findContact(ONLINE)).thenReturn(Optional.of(
                new CustomerContact(ONLINE, "Khách online", OWN_PHONE, ACCOUNT_EMAIL, ACCOUNT, false)));

        assertThatThrownBy(() -> service.decline(ACCOUNT)).isInstanceOf(InvalidStateTransitionException.class)
                .satisfies(ex -> assertThat(((InvalidStateTransitionException) ex).errorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(customers, never()).declineLink(anyLong());
    }

    @Test
    void declineRefusesLockedAccount() {
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);

        assertRule(() -> service.decline(ACCOUNT), "BR-TK-11", LoginAttemptService.MSG_LOCKED);
        verifyNoInteractions(customerQueries, customers);
    }

    private static void assertRule(Runnable action, String ruleId, String message) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(message + " (" + ruleId + ")")
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo(ruleId));
    }
}
