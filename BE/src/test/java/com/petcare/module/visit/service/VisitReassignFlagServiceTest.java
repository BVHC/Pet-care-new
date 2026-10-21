package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link VisitReassignFlagService} (D017, docs/adr/0062): {@code MANDATORY}, {@code updated_at} từ {@code Clock} cắt về
 * µs, một câu UPDATE theo người bị khóa; repository không xóa persistence context của transaction khóa (F3).
 */
class VisitReassignFlagServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T02:15:30.123456789Z");

    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitReassignFlagService service = new VisitReassignFlagService(visits,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @Test
    void flagsTheLockedAccountsCalledVisitsAtTheClockInstant() {
        doReturn(2).when(visits).flagNeedsReassign(45L, NOW.truncatedTo(ChronoUnit.MICROS));

        assertThat(service.flagCalledVisitsOf(45L)).isEqualTo(2);

        verify(visits).flagNeedsReassign(45L, NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void nullAccountIsAProgrammingError() {
        assertThatThrownBy(() -> service.flagCalledVisitsOf(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(visits);
    }

    @Test
    void serviceAndRepositoryJoinTheLockTransaction() throws Exception {
        Method serviceMethod = VisitReassignFlagService.class.getMethod("flagCalledVisitsOf", Long.class);
        assertThat(serviceMethod.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);

        Method repositoryMethod = VisitRepository.class.getMethod("flagNeedsReassign", Long.class, Instant.class);
        assertThat(repositoryMethod.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        Modifying modifying = repositoryMethod.getAnnotation(Modifying.class);
        assertThat(modifying.flushAutomatically()).as("ghi Account đang sửa trước câu native").isTrue();
        assertThat(modifying.clearAutomatically()).as("không tách entity của identity (F3)").isFalse();
    }
}
