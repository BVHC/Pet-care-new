package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * {@link VisitCodeGenerator} (docs/adr/0058): loại dãy {@code visits}, tiền tố {@code TN}, tháng theo ngày nghiệp vụ
 * Việt Nam của {@code Clock}; transaction readOnly bị chặn trước khi chạm bộ đếm.
 */
class VisitCodeGeneratorTest {

    private final DocumentCodeGenerator codes = mock(DocumentCodeGenerator.class);

    @AfterEach
    void resetReadOnly() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void usesVisitSequenceWithBusinessDate() {
        // 2026-10-31T17:30Z là 00:30 ngày 01/11 giờ Việt Nam: tháng của dãy là 11, không phải 10.
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T17:30:00Z"), TimeConfig.BUSINESS_ZONE);
        when(codes.next("visits", "TN", LocalDate.of(2026, 11, 1))).thenReturn("TN-2611-00001");

        assertThat(new VisitCodeGenerator(codes, clock).nextCode()).isEqualTo("TN-2611-00001");
    }

    @Test
    void readOnlyTransactionIsRejectedBeforeTheCounter() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        VisitCodeGenerator generator = new VisitCodeGenerator(codes, Clock.systemUTC());

        assertThatThrownBy(generator::nextCode).isInstanceOf(IllegalStateException.class);
        verify(codes, never()).next(anyString(), anyString(), any());
    }
}
