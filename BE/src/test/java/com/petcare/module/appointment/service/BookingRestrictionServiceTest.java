package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.entity.BookingRestriction;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * {@link BookingRestrictionService} (docs/adr/0047, 0049): {@code isRestricted} hỏi repository, chỉ đọc;
 * {@code evaluateAfterViolation} khóa theo khách trước, đếm vi phạm sau
 * {@code max(at − window, lần hạn chế gần nhất)} từ cả lịch hẹn và đặt chỗ, đủ ngưỡng mới tạo đúng một dòng.
 */
class BookingRestrictionServiceTest {

    private static final Instant AT = Instant.parse("2026-10-15T02:00:00Z");
    private static final long CUSTOMER = 5L;

    private final BookingRestrictionRepository repository = mock(BookingRestrictionRepository.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final AppointmentLocks locks = mock(AppointmentLocks.class);
    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final BookingRestrictionService service = new BookingRestrictionService(repository, appointments, locks,
            boarding, configs);

    @BeforeEach
    void defaults() {
        when(configs.getInt(ConfigKey.BOOKING_RESTRICTION_WINDOW_DAYS)).thenReturn(90);
        when(configs.getInt(ConfigKey.BOOKING_RESTRICTION_VIOLATION_THRESHOLD)).thenReturn(3);
        when(configs.getInt(ConfigKey.BOOKING_RESTRICTION_DURATION_DAYS)).thenReturn(30);
        when(repository.findLatestStartsAt(CUSTOMER)).thenReturn(Optional.empty());
    }

    @Test
    void isRestrictedAsksTheRepositoryAtTheGivenInstant() throws NoSuchMethodException {
        when(repository.existsActive(5L, AT)).thenReturn(true);

        assertThat(service.isRestricted(5L, AT)).isTrue();
        assertThat(service.isRestricted(6L, AT)).isFalse();
        assertThat(BookingRestrictionService.class.getMethod("isRestricted", Long.class, Instant.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> service.isRestricted(null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.isRestricted(5L, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.evaluateAfterViolation(null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.evaluateAfterViolation(5L, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository, appointments, locks, boarding);
    }

    @Test
    void evaluateIsMandatoryWithoutNoRollbackFor() throws NoSuchMethodException {
        Transactional tx = BookingRestrictionService.class
                .getMethod("evaluateAfterViolation", Long.class, Instant.class).getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    @Test
    void thresholdReachedCreatesOneRestrictionForTheConfiguredDuration() {
        Instant since = AT.minus(Duration.ofDays(90));
        when(appointments.countViolationsAfter(CUSTOMER, since)).thenReturn(2);
        when(boarding.countViolations(CUSTOMER, since)).thenReturn(1);

        service.evaluateAfterViolation(CUSTOMER, AT);

        ArgumentCaptor<BookingRestriction> saved = ArgumentCaptor.forClass(BookingRestriction.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(saved.getValue().getStartsAt()).isEqualTo(AT);
        assertThat(saved.getValue().getEndsAt()).isEqualTo(AT.plus(Duration.ofDays(30)));
        assertThat(saved.getValue().getViolationCount()).isEqualTo((short) 3);
        assertThat(saved.getValue().getLiftedAt()).isNull();
    }

    @Test
    void belowThresholdCreatesNothing() {
        Instant since = AT.minus(Duration.ofDays(90));
        when(appointments.countViolationsAfter(CUSTOMER, since)).thenReturn(1);
        when(boarding.countViolations(CUSTOMER, since)).thenReturn(1);

        service.evaluateAfterViolation(CUSTOMER, AT);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void customerIsLockedBeforeAnyRead() {
        when(appointments.countViolationsAfter(any(), any())).thenReturn(3);

        service.evaluateAfterViolation(CUSTOMER, AT);

        InOrder order = inOrder(locks, repository, appointments, boarding);
        order.verify(locks).lockCustomerViolations(CUSTOMER);
        order.verify(repository).existsActive(CUSTOMER, AT);
        order.verify(repository).findLatestStartsAt(CUSTOMER);
        order.verify(appointments).countViolationsAfter(any(), any());
        order.verify(boarding).countViolations(any(), any());
        order.verify(repository).saveAndFlush(any());
    }

    @Test
    void activeRestrictionMeansNoSecondRow() {
        when(repository.existsActive(CUSTOMER, AT)).thenReturn(true);

        service.evaluateAfterViolation(CUSTOMER, AT);

        verify(locks).lockCustomerViolations(CUSTOMER);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(appointments, boarding);
    }

    /** Q3: vi phạm đã gây ra lần hạn chế trước không bị đếm lại (đếm sau starts_at của lần đó). */
    @Test
    void countingStartsAfterTheLatestRestrictionInsideTheWindow() {
        Instant latest = AT.minus(Duration.ofDays(40));
        when(repository.findLatestStartsAt(CUSTOMER)).thenReturn(Optional.of(latest));

        service.evaluateAfterViolation(CUSTOMER, AT);

        verify(appointments).countViolationsAfter(CUSTOMER, latest);
        verify(boarding).countViolations(CUSTOMER, latest);
    }

    @Test
    void restrictionOlderThanTheWindowDoesNotWidenIt() {
        when(repository.findLatestStartsAt(CUSTOMER)).thenReturn(Optional.of(AT.minus(Duration.ofDays(200))));

        service.evaluateAfterViolation(CUSTOMER, AT);

        verify(appointments).countViolationsAfter(CUSTOMER, AT.minus(Duration.ofDays(90)));
    }

    @Test
    void boardingFailureEscapesSoTheCallerRollsBack() {
        when(boarding.countViolations(any(), any())).thenThrow(new UnsupportedOperationException("boarding chưa cài"));

        assertThatThrownBy(() -> service.evaluateAfterViolation(CUSTOMER, AT))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(repository, never()).saveAndFlush(any());
    }
}
