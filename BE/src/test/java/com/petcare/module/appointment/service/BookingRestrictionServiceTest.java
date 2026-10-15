package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.repository.BookingRestrictionRepository;

/**
 * {@link BookingRestrictionService} (docs/adr/0043): {@code isRestricted} hỏi repository, chỉ đọc;
 * {@code evaluateAfterViolation} chưa cài — ném lỗi, không âm thầm bỏ qua (06-module-contracts §1, nợ D030).
 */
class BookingRestrictionServiceTest {

    private static final Instant AT = Instant.parse("2026-10-15T02:00:00Z");

    private final BookingRestrictionRepository repository = mock(BookingRestrictionRepository.class);
    private final BookingRestrictionService service = new BookingRestrictionService(repository);

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
        verifyNoInteractions(repository);
    }

    @Test
    void evaluateAfterViolationIsNotBuiltYet() {
        assertThatThrownBy(() -> service.evaluateAfterViolation(5L, AT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(BookingRestrictionService.EVALUATE_NOT_READY);
        verifyNoInteractions(repository);
    }
}
