package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * Mã lịch hẹn (docs/adr/0041): {@code LH-yyMMdd-NNNN}, ngày theo giờ Việt Nam kể cả khi {@code Clock} mang múi giờ
 * khác, đệm 0 tới 4 chữ số, tối đa 20 ký tự ở {@code MAXVALUE} của sequence; transaction readOnly bị chặn trước SQL.
 */
class AppointmentCodeGeneratorTest {

    private final AppointmentRepository repository = mock(AppointmentRepository.class);

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private AppointmentCodeGenerator generatorAt(String instant, java.time.ZoneId zone) {
        return new AppointmentCodeGenerator(repository, Clock.fixed(Instant.parse(instant), zone));
    }

    @Test
    void formatsBusinessDateAndPaddedNumber() {
        when(repository.nextCodeValue()).thenReturn(7L);

        assertThat(generatorAt("2026-10-15T03:00:00Z", TimeConfig.BUSINESS_ZONE).nextCode())
                .isEqualTo("LH-261015-0007");
    }

    /** 23:59 và 00:00 giờ Việt Nam (UTC+7) — ngày trong mã theo giờ Việt Nam, không theo múi giờ của Clock. */
    @Test
    void dateFollowsVietnamTimeEvenWithUtcClock() {
        when(repository.nextCodeValue()).thenReturn(1L);

        assertThat(generatorAt("2026-10-15T16:59:59Z", ZoneOffset.UTC).nextCode()).isEqualTo("LH-261015-0001");
        assertThat(generatorAt("2026-10-15T17:00:00Z", ZoneOffset.UTC).nextCode()).isEqualTo("LH-261016-0001");
    }

    @Test
    void numberGrowsBeyondFourDigitsWithinColumnLimit() {
        LocalDate day = LocalDate.of(2026, 10, 15);

        assertThat(AppointmentCodeGenerator.format(day, 12_345L)).isEqualTo("LH-261015-12345");
        assertThat(AppointmentCodeGenerator.format(day, 9_999_999_999L))
                .isEqualTo("LH-261015-9999999999").hasSize(20);
    }

    @Test
    void readOnlyTransactionIsRejectedBeforeAnySql() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> generatorAt("2026-10-15T03:00:00Z", TimeConfig.BUSINESS_ZONE).nextCode())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        verify(repository, never()).nextCodeValue();
    }

    @Test
    void nextCodeIsMandatoryWithoutNoRollbackFor() throws NoSuchMethodException {
        Transactional tx = AppointmentCodeGenerator.class.getMethod("nextCode").getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(tx.noRollbackFor()).isEmpty();
    }
}
