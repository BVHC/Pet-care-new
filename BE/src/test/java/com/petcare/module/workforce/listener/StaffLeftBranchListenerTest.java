package com.petcare.module.workforce.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

import com.petcare.module.identity.api.StaffLeftBranchEvent;
import com.petcare.module.workforce.service.StaffLeftBranchService;

/**
 * {@link StaffLeftBranchListener} (BR-NS-09, docs/adr/0138 bước 5): chỉ ủy quyền cho service, đồng bộ trong transaction
 * của identity (convention 07 §7.2) — không {@code @Async}, không {@code @TransactionalEventListener} (AFTER_COMMIT) và
 * không nuốt lỗi.
 */
class StaffLeftBranchListenerTest {

    private final StaffLeftBranchService service = mock(StaffLeftBranchService.class);
    private final StaffLeftBranchListener listener = new StaffLeftBranchListener(service);

    @Test
    void delegatesTheEventsIdsUnchangedAndDoesNothingElse() {
        listener.on(new StaffLeftBranchEvent(12L, 5L));

        verify(service).cancelUpcomingShiftsOf(12L, 5L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void aFailureOfTheServicePropagatesSoTheIdentityTransactionRollsBack() {
        when(service.cancelUpcomingShiftsOf(12L, 5L)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> listener.on(new StaffLeftBranchEvent(12L, 5L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void isASynchronousInTransactionListener() throws Exception {
        Method on = StaffLeftBranchListener.class.getMethod("on", StaffLeftBranchEvent.class);

        assertThat(on.getAnnotation(EventListener.class)).isNotNull();
        assertThat(on.getAnnotation(TransactionalEventListener.class)).as("không AFTER_COMMIT").isNull();
        assertThat(on.getAnnotation(Async.class)).as("không @Async").isNull();
        assertThat(StaffLeftBranchListener.class.getAnnotation(Async.class)).isNull();
    }
}
