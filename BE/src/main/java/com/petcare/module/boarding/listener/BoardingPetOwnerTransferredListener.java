package com.petcare.module.boarding.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.service.BoardingClinicCancellationService;
import com.petcare.module.boarding.service.BoardingClinicCause;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;

/**
 * BR-KH-08: thú được chuyển chủ → mọi đặt chỗ {@code BOOKED} của thú → {@code CANCELLED} (Đặt chỗ#5, không tính hủy muộn); thông báo báo chủ lúc đặt (chủ cũ). Đồng bộ, trong transaction của bên phát (convention 07 §7.2): không {@code @Async}, không bắt lỗi — lỗi ở đây
 * rollback cả bên phát (docs/adr/0065).
 */
@Component
public class BoardingPetOwnerTransferredListener {

    private final BoardingClinicCancellationService cancellations;

    public BoardingPetOwnerTransferredListener(BoardingClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(PetOwnerTransferredEvent event) {
        cancellations.cancelBookedOfPet(event.petId(), BoardingClinicCause.PET_OWNER_TRANSFERRED);
    }
}
