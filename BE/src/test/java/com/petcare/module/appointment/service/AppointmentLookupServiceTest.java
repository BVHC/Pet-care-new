package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.appointment.repository.AppointmentRepository;

/** {@link AppointmentLookupService} (docs/adr/0046): chỉ ủy quyền cho repository, tham số null bị chặn trước SQL. */
class AppointmentLookupServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final AppointmentLookupService service = new AppointmentLookupService(appointments);

    private static BookedSlot slot(long id) {
        return new BookedSlot(id, "LH-" + id, 3L, 9L, 7L, DAY, LocalTime.of(9, 0));
    }

    @Test
    void delegatesEveryQuery() {
        when(appointments.findBookedFrom(3L, DAY)).thenReturn(List.of(slot(1)));
        when(appointments.findBookedByCustomerId(7L)).thenReturn(List.of(slot(2)));
        when(appointments.existsByPetId(9L)).thenReturn(true);
        when(appointments.existsByCustomerId(7L)).thenReturn(true);

        assertThat(service.findBookedFrom(3L, DAY)).containsExactly(slot(1));
        assertThat(service.findBookedByCustomer(7L)).containsExactly(slot(2));
        assertThat(service.existsByPet(9L)).isTrue();
        assertThat(service.existsByCustomer(7L)).isTrue();
        assertThat(service.existsByPet(10L)).isFalse();
        assertThat(service.findBookedFrom(4L, DAY)).isEmpty();
    }

    @Test
    void nullArgumentsRejectedBeforeAnySql() {
        assertThatThrownBy(() -> service.findBookedFrom(null, DAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.findBookedFrom(3L, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.existsByPet(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.existsByCustomer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.findBookedByCustomer(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(appointments);
    }

    /** Tham gia transaction ghi của bên gọi (REQUIRED, readOnly): không tự mở transaction mới. */
    @Test
    void joinsCallerTransactionAsReadOnly() {
        Transactional tx = AppointmentLookupService.class.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }
}
