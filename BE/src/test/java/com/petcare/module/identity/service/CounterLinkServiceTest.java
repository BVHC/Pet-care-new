package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.CounterLinkRequest;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.VerificationMethod;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Facade của UC22 liên kết hộ (identity-v1 #27): hai bước ở {@link CounterLinkStepService} (mock — logic của nó có test
 * riêng) phải chạy ở hai transaction riêng, nên facade KHÔNG {@code @Transactional}.
 */
class CounterLinkServiceTest {

    private static final long CUSTOMER = 7L;

    private final CounterLinkStepService steps = mock(CounterLinkStepService.class);
    private final CounterLinkService facade = new CounterLinkService(steps);

    @Test
    void facadeIsNotTransactional() {
        assertThat(CounterLinkService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(CounterLinkService.class.getDeclaredMethods())
                .filter((Method method) -> method.isAnnotationPresent(Transactional.class))).isEmpty();
    }

    /** Đọc hồ sơ trước, rồi mới ghi; email tài khoản chuẩn hóa chữ thường, email hồ sơ đi nguyên văn. */
    @Test
    void readsTheProfileThenAppliesWithNormalizedAccountEmailAndVerbatimProfileEmail() {
        when(steps.findCounterEmail(CUSTOMER)).thenReturn("Quay7@PetCare.test");
        when(steps.applyLink(CUSTOMER, "khach70@petcare.test", "Quay7@PetCare.test"))
                .thenReturn(new LinkResult(CUSTOMER));

        LinkResult result = facade.linkAccount(CUSTOMER, request(" Khach70@PetCare.TEST "));

        assertThat(result).isEqualTo(new LinkResult(CUSTOMER));
        InOrder order = inOrder(steps);
        order.verify(steps).findCounterEmail(CUSTOMER);
        order.verify(steps).applyLink(CUSTOMER, "khach70@petcare.test", "Quay7@PetCare.test");
    }

    /** 404 / BR-TK-19 của bước đọc đi thẳng ra ngoài: chưa khóa, chưa ghi gì. */
    @Test
    void errorsOfTheReadStepPassThroughBeforeAnyWrite() {
        BusinessRuleViolationException rejected = new BusinessRuleViolationException("BR-TK-19", "bước đọc");
        when(steps.findCounterEmail(CUSTOMER)).thenThrow(rejected);

        assertThatThrownBy(() -> facade.linkAccount(CUSTOMER, request("khach70@petcare.test"))).isSameAs(rejected);

        verify(steps, never()).applyLink(anyLong(), anyString(), any());
    }

    private static CounterLinkRequest request(String accountEmail) {
        return new CounterLinkRequest(accountEmail, VerificationMethod.ID_CARD_IN_PERSON);
    }
}
