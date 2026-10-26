package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.repository.CareTaskWriteRepository;

/** {@link CareTaskService} một phần (docs/adr/0074, D047): chỉ hủy task tái chủng có thật, hai method còn lại ném lỗi. */
class CareTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    private final CareTaskWriteRepository tasks = mock(CareTaskWriteRepository.class);
    private final CareTaskService service = new CareTaskService(tasks, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));

    @Test
    void emptyIdsNeverTouchTheRepository() {
        service.cancelForRevaccination(List.of());

        verifyNoInteractions(tasks);
    }

    @Test
    void idsAreCancelledWithTheSystemReasonAtTheClockTime() {
        service.cancelForRevaccination(List.of(600L, 601L));

        verify(tasks).cancelOpenVaccineTasks(List.of(600L, 601L), CareTaskService.REVACCINATED_REASON, NOW);
    }

    @Test
    void createAndFollowUpCancellationAreNotBuiltUntilT39() {
        assertThatThrownBy(() -> service.create(new NewCareTask(CareTaskType.VACCINE_DUE, 1L, 2L, 3L, 4L,
                LocalDate.of(2026, 11, 1)))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.cancelFollowUpTasks(List.of(1L))).isInstanceOf(UnsupportedOperationException.class);

        verify(tasks, never()).cancelOpenVaccineTasks(anyCollection(), anyString(), any());
    }
}
