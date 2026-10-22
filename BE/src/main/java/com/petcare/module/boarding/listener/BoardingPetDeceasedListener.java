package com.petcare.module.boarding.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.service.BoardingClinicCancellationService;
import com.petcare.module.boarding.service.BoardingClinicCause;
import com.petcare.module.customer.api.PetDeceasedEvent;

/**
 * BR-KH-05: thú được đánh dấu đã mất (sau khi đã kiểm không đang lưu trú) → mọi đặt chỗ {@code BOOKED} của thú → {@code CANCELLED} (Đặt chỗ#5, không tính hủy muộn). Đồng bộ, trong transaction của bên phát (convention 07 §7.2): không {@code @Async}, không bắt lỗi — lỗi ở đây
 * rollback cả bên phát (docs/adr/0065).
 */
@Component
public class BoardingPetDeceasedListener {

    private final BoardingClinicCancellationService cancellations;

    public BoardingPetDeceasedListener(BoardingClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(PetDeceasedEvent event) {
        cancellations.cancelBookedOfPet(event.petId(), BoardingClinicCause.PET_DECEASED);
    }
}
