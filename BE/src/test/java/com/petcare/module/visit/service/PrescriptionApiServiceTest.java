package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.repository.PrescriptionItemRepository;

/**
 * {@link PrescriptionApiService} (docs/adr/0076): null chặn trước khi chạm DB, một câu UPDATE với {@code now} lấy từ clock,
 * 0 dòng là lỗi của bên gọi, {@code MANDATORY}.
 */
class PrescriptionApiServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    private final PrescriptionItemRepository items = mock(PrescriptionItemRepository.class);
    private final PrescriptionApiService service = new PrescriptionApiService(items,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void marksTheLineAsExternalWithTheClockInstant() {
        doReturn(1).when(items).markExternalAtPayment(9101L, NOW);

        service.markExternalPurchaseAtPayment(9101L, 45L);

        verify(items).markExternalAtPayment(9101L, NOW);
    }

    @Test
    void nullArgumentsFailBeforeTouchingTheRepository() {
        assertThatThrownBy(() -> service.markExternalPurchaseAtPayment(null, 45L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.markExternalPurchaseAtPayment(9101L, null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(items);
    }

    @Test
    void aLineThatIsNotAPrescriptionLineIsTheCallersError() {
        doReturn(0).when(items).markExternalAtPayment(any(), any());

        assertThatThrownBy(() -> service.markExternalPurchaseAtPayment(9101L, 45L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#9101");
    }

    @Test
    void joinsTheCallersTransactionAndNeverStartsItsOwn() throws NoSuchMethodException {
        Transactional tx = PrescriptionApiService.class
                .getMethod("markExternalPurchaseAtPayment", Long.class, Long.class).getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
