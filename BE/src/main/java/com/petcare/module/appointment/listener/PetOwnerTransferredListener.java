package com.petcare.module.appointment.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.appointment.service.AppointmentClinicCancellationService;
import com.petcare.module.appointment.service.ClinicCancelCause;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;

/**
 * BR-KH-08 (UC26): chuyển chủ → mọi lịch {@code BOOKED} của thú qua Lịch hẹn#5 (docs/adr/0052). Thông báo đi tới chủ
 * lúc đặt ({@code appointments.customer_id} = chủ cũ, Q8), nên {@code toCustomerId} không dùng. Đồng bộ, trong
 * transaction của customer (convention 07 §7.2).
 */
@Component
public class PetOwnerTransferredListener {

    private final AppointmentClinicCancellationService cancellations;

    public PetOwnerTransferredListener(AppointmentClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(PetOwnerTransferredEvent event) {
        cancellations.cancelBookedOfPet(event.petId(), ClinicCancelCause.PET_OWNER_TRANSFERRED);
    }
}
