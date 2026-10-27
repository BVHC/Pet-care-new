package com.petcare.module.visit.listener;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.visit.service.FollowUpCancellationService;

/**
 * BR-KH-05, BR-TB-03 (ST19): thú đánh dấu đã mất → hủy nhắc tái khám còn chờ ({@code follow_up_cancelled_at}). Đồng bộ,
 * trong transaction của customer (convention 07 §7.2): không {@code @Async}, không bắt lỗi.
 * <p>
 * {@link #ORDER} nhỏ hơn thứ tự của {@code CarePetDeceasedListener}: visit khóa {@code medical_records} <b>trước</b> care
 * khóa {@code care_tasks}, cùng thứ tự với đặt lịch và check-in (docs/adr/0084). Đảo hai listener sẽ tạo vòng chờ khi thú
 * mất chạy song song với đặt lịch (D027 còn mở: luồng thú mất không lấy advisory 1001).
 */
@Component
public class VisitPetDeceasedListener {

    public static final int ORDER = 100;

    private final FollowUpCancellationService followUps;

    public VisitPetDeceasedListener(FollowUpCancellationService followUps) {
        this.followUps = followUps;
    }

    @EventListener
    @Order(ORDER)
    public void on(PetDeceasedEvent event) {
        followUps.cancelForDeceasedPet(event.petId());
    }
}
