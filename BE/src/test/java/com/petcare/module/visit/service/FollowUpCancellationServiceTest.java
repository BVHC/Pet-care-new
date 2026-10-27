package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.visit.repository.MedicalRecordRepository;

/**
 * {@link FollowUpCancellationService} (docs/adr/0084, trả D029): thứ tự UPDATE → SELECT → hủy task (ST04 chen vào giữa
 * vẫn bị bắt), thú mất không chạm care, mốc từ {@code Clock}, mọi method {@code MANDATORY}, không nuốt lỗi.
 */
class FollowUpCancellationServiceTest {

    private static final long PET = 33L;
    private static final Instant AT = Instant.parse("2026-10-27T03:00:00Z");

    private final MedicalRecordRepository records = mock(MedicalRecordRepository.class);
    private final CareTaskApi careTasks = mock(CareTaskApi.class);
    private final FollowUpCancellationService service = new FollowUpCancellationService(records, careTasks,
            Clock.fixed(AT.plusNanos(123), ZoneId.of("Asia/Ho_Chi_Minh")));

    @Test
    void cancelsPendingRemindersFirstThenReadsRemindedRecordsThenCancelsTheirTasks() {
        when(records.findRemindedFollowUpIdsOfPet(PET)).thenReturn(List.of(701L, 702L));

        service.cancelPendingFollowUps(PET, AT);

        InOrder order = inOrder(records, careTasks);
        order.verify(records).cancelPendingFollowUpsOfPet(PET, AT);
        order.verify(records).findRemindedFollowUpIdsOfPet(PET);
        order.verify(careTasks).cancelFollowUpTasks(List.of(701L, 702L));
        verifyNoMoreInteractions(records, careTasks);
    }

    @Test
    void withNoRemindedRecordsTheCareCallReceivesAnEmptyCollectionWhichCareIgnores() {
        when(records.findRemindedFollowUpIdsOfPet(PET)).thenReturn(List.of());

        service.cancelPendingFollowUps(PET, AT);

        verify(careTasks).cancelFollowUpTasks(List.of());
    }

    @Test
    void deceasedPetOnlyCancelsPendingRemindersAtTheClockTimeTruncatedToMicros() {
        service.cancelForDeceasedPet(PET);

        verify(records).cancelPendingFollowUpsOfPet(PET, AT);
        verifyNoMoreInteractions(records);
        verifyNoInteractions(careTasks);
    }

    @Test
    void aFailureFromTheUpdateIsNotSwallowedAndNothingElseRuns() {
        doThrow(new IllegalStateException("boom")).when(records).cancelPendingFollowUpsOfPet(PET, AT);

        assertThatThrownBy(() -> service.cancelPendingFollowUps(PET, AT)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(careTasks);
    }

    @Test
    void aFailureFromCareIsNotSwallowed() {
        doThrow(new IllegalStateException("boom")).when(careTasks).cancelFollowUpTasks(List.of());

        assertThatThrownBy(() -> service.cancelPendingFollowUps(PET, AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyMethodJoinsTheCallersTransactionAndNeverStartsOne() throws NoSuchMethodException {
        for (Method method : new Method[] {
                FollowUpCancellationService.class.getMethod("cancelPendingFollowUps", long.class, Instant.class),
                FollowUpCancellationService.class.getMethod("cancelForDeceasedPet", long.class)}) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }
}
