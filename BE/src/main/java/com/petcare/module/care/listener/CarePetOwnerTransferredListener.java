package com.petcare.module.care.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.CareTaskService;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;

/**
 * BR-KH-08, 06 §8 Q1: thú được chuyển chủ → Care Task {@code OPEN} của thú chuyển sang chủ mới (giữ loại, chi nhánh,
 * hạn; không hủy rồi sinh lại vì unique "1 task quá hạn / mũi" không phân biệt trạng thái). Đồng bộ, trong transaction của
 * customer (convention 07 §7.2): không {@code @Async}, không bắt lỗi (docs/adr/0084).
 */
@Component
public class CarePetOwnerTransferredListener {

    private final CareTaskService careTasks;

    public CarePetOwnerTransferredListener(CareTaskService careTasks) {
        this.careTasks = careTasks;
    }

    @EventListener
    public void on(PetOwnerTransferredEvent event) {
        careTasks.moveOpenTasksToOwner(event.petId(), event.toCustomerId());
    }
}
