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

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.CustomerEmailChangeRequest;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.VerificationMethod;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Facade của UC22 (identity-v1 #25, #26): BCrypt (sinh mã, so mã) chạy ở đây, ngoài mọi transaction (docs/adr/0023,
 * 0034). {@link CustomerEmailChangeService} mock — logic của nó có test riêng.
 */
class CustomerAccountServiceTest {

    private static final long CUSTOMER = 7L;
    private static final PreparedOtp PREPARED = new PreparedOtp("123456", "$2a$otp");
    private static final EmailChangeCode CODE = new EmailChangeCode(41L, "new@petcare.test", "$2a$code");

    private final CustomerEmailChangeService emailChanges = mock(CustomerEmailChangeService.class);
    private final OtpService otps = mock(OtpService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CustomerAccountService facade = new CustomerAccountService(emailChanges, otps, passwordEncoder);

    @Test
    void facadeIsNotTransactional() {
        assertThat(CustomerAccountService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(CustomerAccountService.class.getDeclaredMethods())
                .filter((Method method) -> method.isAnnotationPresent(Transactional.class))).isEmpty();
    }

    @Test
    void startPreparesCodeBeforeTheTransactionAndPassesItThrough() {
        OtpSentResponse sent = new OtpSentResponse(Instant.parse("2026-11-02T03:01:00Z"), null, 6);
        when(otps.prepare()).thenReturn(PREPARED);
        when(emailChanges.issueEmailChangeOtp(CUSTOMER, " New@PetCare.test ", PREPARED)).thenReturn(sent);

        assertThat(facade.startEmailChange(CUSTOMER, new CustomerEmailChangeRequest(" New@PetCare.test ",
                VerificationMethod.ID_CARD_IN_PERSON))).isSameAs(sent);

        InOrder order = inOrder(otps, emailChanges);
        order.verify(otps).prepare();
        order.verify(emailChanges).issueEmailChangeOtp(CUSTOMER, " New@PetCare.test ", PREPARED);
    }

    /** Kết quả so mã đi nguyên vẹn sang bước ghi: sai mã vẫn gọi bước ghi để bộ đếm được commit. */
    @ParameterizedTest(name = "mã khớp = {0}")
    @ValueSource(booleans = {true, false})
    void confirmComparesTheCodeOutsideTheTransactionThenAppliesTheResult(boolean matched) {
        when(emailChanges.findEmailChangeCode(CUSTOMER)).thenReturn(Optional.of(CODE));
        when(passwordEncoder.matches("654321", "$2a$code")).thenReturn(matched);

        facade.confirmEmailChange(CUSTOMER, new OtpCodeRequest("654321"));

        InOrder order = inOrder(emailChanges, passwordEncoder);
        order.verify(emailChanges).findEmailChangeCode(CUSTOMER);
        order.verify(passwordEncoder).matches("654321", "$2a$code");
        order.verify(emailChanges).applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, "new@petcare.test", matched));
    }

    /** Không còn mã đang mở → BR-TK-05, ném sau khi transaction đọc đã kết thúc; không BCrypt, không bước ghi. */
    @Test
    void confirmWithoutOpenCodeIsBrTk05AndNeverWrites() {
        when(emailChanges.findEmailChangeCode(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.confirmEmailChange(CUSTOMER, new OtpCodeRequest("123456")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("OTP không đúng hoặc đã hết hạn (BR-TK-05)");

        verifyNoInteractions(passwordEncoder);
        verify(emailChanges, never()).applyEmailChange(anyLong(), any());
    }

    /** 404 / 400 của bước đọc đi thẳng ra ngoài: chưa so mã, chưa ghi gì. */
    @Test
    void errorsOfTheReadStepPassThroughBeforeAnyBcrypt() {
        RuntimeException notFound = new IllegalStateException("bước đọc");
        when(emailChanges.findEmailChangeCode(CUSTOMER)).thenThrow(notFound);

        assertThatThrownBy(() -> facade.confirmEmailChange(CUSTOMER, new OtpCodeRequest("123456"))).isSameAs(notFound);

        verifyNoInteractions(passwordEncoder);
        verify(emailChanges, never()).applyEmailChange(anyLong(), any());
    }
}
