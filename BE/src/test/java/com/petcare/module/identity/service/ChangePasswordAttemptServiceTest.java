package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.CurrentPasswordMismatchException;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LoginAttemptService.LoginCounterSnapshot;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ErrorCode;

/**
 * UC05 dưới khóa dòng (docs/adr/0022): BR-TK-11 → BR-TK-09 → mật khẩu hiện tại (BR-TK-14, bộ đếm BR-TK-09 dùng chung
 * với đăng nhập) → ghi; các nhánh 400 khác ném trước mọi lệnh ghi; thành công thì ghi entity trước khi hủy phiên khác.
 */
class ChangePasswordAttemptServiceTest {

    private static final long ID = 7L;
    private static final long SESSION_ID = 70L;
    private static final String EMAIL = "nv@petcare.test";
    private static final String CURRENT = "matkhau123";
    private static final String NEW = "matkhaumoi9";
    /** 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final NewPasswordHasher hasher = mock(NewPasswordHasher.class);
    private final ChangePasswordAttemptService service = new ChangePasswordAttemptService(accounts, sessions,
            notifications, configs, audit, encoder, hasher, Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private String hash;
    private Account account;

    @BeforeEach
    void setUp() {
        hash = encoder.encode(CURRENT);
        when(configs.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS)).thenReturn(5);
        when(configs.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)).thenReturn(15);
        when(configs.getInt(ConfigKey.LOGIN_LOCK_MINUTES)).thenReturn(15);
        account = Account.registerCustomer(EMAIL, hash, null);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "role", Role.RECEPTIONIST);
        ReflectionTestUtils.setField(account, "phone", "0900000000");
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        when(accounts.findByIdForUpdate(ID)).thenReturn(Optional.of(account));
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void successWritesAccountThenRevokesOtherSessions() {
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        ReflectionTestUtils.setField(account, "failedLoginCount", 3);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", NOW.minusSeconds(60));

        service.apply(ID, SESSION_ID, hash, CURRENT, NEW, true, "new-hash");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isMustChangePassword()).isFalse();
        assertThat(account.getFailedLoginCount()).isZero();
        InOrder order = inOrder(accounts, sessions);
        order.verify(accounts).findByIdForUpdate(ID);
        order.verify(sessions).revokeOthers(ID, SESSION_ID);
        verify(sessions, never()).revokeAll(anyLong());
        verifyNoInteractions(audit, notifications, hasher);
    }

    // ---------------------------------------------------------------- sai mật khẩu hiện tại (BR-TK-14 → BR-TK-09)

    @Test
    void wrongCurrentCountsThenThrowsBrTk14WithoutAuditOrEmail() {
        assertThatThrownBy(() -> service.apply(ID, SESSION_ID, hash, "sai12345", NEW, false, null))
                .isInstanceOf(CurrentPasswordMismatchException.class)
                .hasMessage("Mật khẩu hiện tại không đúng (BR-TK-14)")
                .satisfies(ex -> assertThat(((CurrentPasswordMismatchException) ex).errorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getFirstFailedLoginAt()).isEqualTo(NOW);
        assertThat(account.getPasswordHash()).isEqualTo(hash);
        verifyNoInteractions(sessions, audit, notifications);
    }

    @Test
    void wrongCurrentReachingThresholdLocksEmailsAuditsThenThrows() {
        ReflectionTestUtils.setField(account, "failedLoginCount", 4);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.apply(ID, SESSION_ID, hash, "sai12345", NEW, false, null))
                .isInstanceOf(CurrentPasswordMismatchException.class)
                .hasMessage("Mật khẩu hiện tại không đúng. Bạn đã nhập sai quá nhiều lần, tài khoản tạm khóa đăng nhập"
                        + " tới 09:15 08/10/2026 (BR-TK-14)");

        Instant until = NOW.plus(Duration.ofMinutes(15));
        assertThat(account.getLockedUntil()).isEqualTo(until);
        assertThat(account.getFailedLoginCount()).isZero();

        ArgumentCaptor<NotificationRequest> mail = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(mail.capture());
        assertThat(mail.getValue().templateCode()).isEqualTo(NotificationTemplateCode.LOGIN_LOCKED_WARNING);
        assertThat(mail.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(mail.getValue().recipientEmail()).isEqualTo(EMAIL);
        assertThat(mail.getValue().payload())
                .isEqualTo(Map.of("so_lan_sai", 5, "thoi_diem_mo_khoa", "09:15 08/10/2026"));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(IdentityAuditActions.ACCOUNT_TEMPORARILY_LOCKED);
        assertThat(entry.getValue().entityType()).isEqualTo("accounts");
        assertThat(entry.getValue().entityId()).isEqualTo(ID);
        assertThat(entry.getValue().before()).isEqualTo(new LoginCounterSnapshot(4, null));
        assertThat(entry.getValue().after()).isEqualTo(new LoginCounterSnapshot(0, until));
        verify(audit, never()).recordIndependently(any());
        verifyNoInteractions(sessions);
    }

    // ---------------------------------------------------------------- chặn trước mọi lệnh ghi

    @Test
    void adminLockedUnderLockIs400BrTk11AndNothingWritten() {
        ReflectionTestUtils.setField(account, "locked", true);

        assertRejectedWithoutWrites("BR-TK-11",
                "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)");
    }

    @Test
    void disabledUnderLockIs400BrTk11AndNothingWritten() {
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);

        assertRejectedWithoutWrites("BR-TK-11",
                "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)");
    }

    /** Đăng nhập song song vừa khóa tạm giữa bước đọc và bước khóa dòng. */
    @Test
    void temporarilyLockedUnderLockIs400BrTk09AndNothingWritten() {
        ReflectionTestUtils.setField(account, "lockedUntil", NOW.plusSeconds(30));

        assertRejectedWithoutWrites("BR-TK-09",
                "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 09:01 08/10/2026 (BR-TK-09)");
    }

    // ---------------------------------------------------------------- mật khẩu vừa đổi song song

    @Test
    void hashChangedSinceReadIsRecheckedUnderLock() {
        String otherHash = encoder.encode("khac12345");
        ReflectionTestUtils.setField(account, "passwordHash", otherHash);

        assertThatThrownBy(() -> service.apply(ID, SESSION_ID, hash, CURRENT, NEW, true, "new-hash"))
                .isInstanceOf(CurrentPasswordMismatchException.class);

        assertThat(account.getPasswordHash()).isEqualTo(otherHash);
        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        verifyNoInteractions(sessions);
    }

    @Test
    void hashChangedAndNowMatchingHashesNewPasswordUnderLock() {
        String readHash = encoder.encode("cu12345678");
        when(hasher.hash(CURRENT, NEW)).thenReturn("new-hash");

        service.apply(ID, SESSION_ID, readHash, CURRENT, NEW, false, null);

        verify(hasher).hash(CURRENT, NEW);
        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        verify(sessions).revokeOthers(ID, SESSION_ID);
    }

    @Test
    void hashChangedAndNewPasswordInvalidIsBrTk03WithoutWrites() {
        String readHash = encoder.encode("cu12345678");
        when(hasher.hash(CURRENT, NEW)).thenThrow(new BusinessRuleViolationException("BR-TK-03", "yếu"));

        assertThatThrownBy(() -> service.apply(ID, SESSION_ID, readHash, CURRENT, NEW, false, null))
                .hasMessageEndingWith("(BR-TK-03)");

        assertThat(account.getPasswordHash()).isEqualTo(hash);
        assertThat(account.getFailedLoginCount()).isZero();
        verifyNoInteractions(sessions, audit, notifications);
    }

    private void assertRejectedWithoutWrites(String ruleId, String message) {
        assertThatThrownBy(() -> service.apply(ID, SESSION_ID, hash, CURRENT, NEW, true, "new-hash"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .isNotInstanceOf(CurrentPasswordMismatchException.class)
                .hasMessage(message)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo(ruleId));

        assertThat(account.getPasswordHash()).isEqualTo(hash);
        assertThat(account.getFailedLoginCount()).isZero();
        verifyNoInteractions(sessions, audit, notifications, hasher);
    }
}
