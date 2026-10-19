package com.petcare.module.appointment.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.appointment.service.AppointmentClinicCancellationService;
import com.petcare.module.appointment.service.ClinicCancelCause;
import com.petcare.module.customer.api.PetDeceasedEvent;

/**
 * BR-KH-05 (UC24): thú đánh dấu đã mất → mọi lịch {@code BOOKED} của thú qua Lịch hẹn#5 (docs/adr/0047). Đồng bộ, trong
 * transaction của customer (convention 07 §7.2).
 */
@Component
public class PetDeceasedListener {

    private final AppointmentClinicCancellationService cancellations;

    public PetDeceasedListener(AppointmentClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(PetDeceasedEvent event) {
        cancellations.cancelBookedOfPet(event.petId(), ClinicCancelCause.PET_DECEASED);
    }
}
