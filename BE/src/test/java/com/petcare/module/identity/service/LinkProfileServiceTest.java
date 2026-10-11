package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkConfirmRequest;
import com.petcare.module.identity.dto.LinkOtpRequest;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkAttempt;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkCode;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * Facade UC07 không transaction (docs/adr/0027): luôn dùng tài khoản của chủ token; sinh mã trước khi ghi; so BCrypt giữa
 * bước đọc và bước ghi; không có mã → BR-TK-05 và không ghi gì.
 */
class LinkProfileServiceTest {

    private static final long ACCOUNT = 9L;
    private static final long COUNTER = 70L;

    private final LinkProfileAttemptService attempts = mock(LinkProfileAttemptService.class);
    private final OtpService otps = mock(OtpService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final LinkProfileService service = new LinkProfileService(attempts, otps, passwordEncoder, branchScope);

    @BeforeEach
    void setUp() {
        when(branchScope.current())
                .thenReturn(new AccountPrincipal(ACCOUNT, "khach@petcare.test", 5L, Role.CUSTOMER, null, false));
    }

    @Test
    void candidatesAreReadForTheTokenOwner() {
        List<LinkCandidateResponse> list = List.of(new LinkCandidateResponse(COUNTER, "Ng*** A", true));
        when(attempts.listCandidates(ACCOUNT, "0901234567")).thenReturn(list);

        assertThat(service.listCandidates("0901234567")).isSameAs(list);
    }

    @Test
    void codeIsPreparedBeforeTheWritingTransaction() {
        OtpService.PreparedOtp prepared = new OtpService.PreparedOtp("123456", "hash");
        when(otps.prepare()).thenReturn(prepared);

        service.sendLinkOtp(new LinkOtpRequest(COUNTER, null));

        InOrder order = inOrder(otps, attempts);
        order.verify(otps).prepare();
        order.verify(attempts).issueLinkOtp(ACCOUNT, COUNTER, null, prepared);
    }

    @Test
    void confirmMatchesOutsideTheTransactionThenApplies() {
        when(attempts.findLinkCode(ACCOUNT, COUNTER))
                .thenReturn(Optional.of(new LinkCode(COUNTER, "ho-so@petcare.test", 501L, "code-hash")));
        when(passwordEncoder.matches("123456", "code-hash")).thenReturn(true);
        when(attempts.applyLink(anyLong(), any())).thenReturn(new LinkResult(COUNTER));

        assertThat(service.confirmLink(new LinkConfirmRequest(COUNTER, "123456"))).isEqualTo(new LinkResult(COUNTER));

        InOrder order = inOrder(attempts, passwordEncoder);
        order.verify(attempts).findLinkCode(ACCOUNT, COUNTER);
        order.verify(passwordEncoder).matches("123456", "code-hash");
        order.verify(attempts).applyLink(ACCOUNT, new LinkAttempt(COUNTER, "ho-so@petcare.test", 501L, true));
    }

    @Test
    void wrongCodeIsStillAppliedSoTheCounterIsWritten() {
        when(attempts.findLinkCode(ACCOUNT, COUNTER))
                .thenReturn(Optional.of(new LinkCode(COUNTER, "ho-so@petcare.test", 501L, "code-hash")));
        when(passwordEncoder.matches("000000", "code-hash")).thenReturn(false);

        service.confirmLink(new LinkConfirmRequest(COUNTER, "000000"));

        verify(attempts).applyLink(ACCOUNT, new LinkAttempt(COUNTER, "ho-so@petcare.test", 501L, false));
    }

    @Test
    void noOpenCodeIsBrTk05WithoutWriting() {
        when(attempts.findLinkCode(ACCOUNT, COUNTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmLink(new LinkConfirmRequest(COUNTER, "123456")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-05)");
        verify(attempts, never()).applyLink(anyLong(), any());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void declineUsesTheTokenOwner() {
        service.declineLink();

        verify(attempts).decline(ACCOUNT);
    }
}
