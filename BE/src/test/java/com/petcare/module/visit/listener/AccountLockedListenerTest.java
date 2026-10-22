package com.petcare.module.visit.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

import com.petcare.module.identity.api.AccountLockedEvent;
import com.petcare.module.identity.api.Role;
import com.petcare.module.visit.service.VisitReassignFlagService;

/** {@link AccountLockedListener} (D017, docs/adr/0062): listener đồng bộ, chỉ ủy quyền cho service (convention 07 §7.2). */
class AccountLockedListenerTest {

    private final VisitReassignFlagService flags = mock(VisitReassignFlagService.class);
    private final AccountLockedListener listener = new AccountLockedListener(flags);

    @Test
    void delegatesTheLockedAccount() {
        listener.on(new AccountLockedEvent(45L, Role.VET, 3L, "Vi phạm", 1L));

        verify(flags).flagCalledVisitsOf(45L);
        verifyNoMoreInteractions(flags);
    }

    @Test
    void isASynchronousListener() throws Exception {
        Method method = AccountLockedListener.class.getMethod("on", AccountLockedEvent.class);
        assertThat(method.getAnnotation(EventListener.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNull();
    }
}
