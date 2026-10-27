package com.petcare.module.care.listener;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.CareTaskService;
import com.petcare.module.customer.api.PetDeceasedEvent;

/**
 * BR-KH-05, BR-TB-03 (ST19, Care Task#4): thú đánh dấu đã mất → mọi Care Task {@code OPEN} của thú → {@code CANCELLED}.
 * Đồng bộ, trong transaction của customer (convention 07 §7.2): không {@code @Async}, không bắt lỗi.
 * <p>
 * {@link #ORDER} <b>lớn hơn</b> {@code VisitPetDeceasedListener.ORDER} (100): care khóa {@code care_tasks} sau khi visit
 * khóa {@code medical_records} (docs/adr/0084). Hai module không import nhau nên quan hệ này do
 * {@code PetDeceasedListenerOrderTest} ghim.
 */
@Component
public class CarePetDeceasedListener {

    public static final int ORDER = 200;

    private final CareTaskService careTasks;

    public CarePetDeceasedListener(CareTaskService careTasks) {
        this.careTasks = careTasks;
    }

    @EventListener
    @Order(ORDER)
    public void on(PetDeceasedEvent event) {
        careTasks.cancelOpenTasksOfDeceasedPet(event.petId());
    }
}
