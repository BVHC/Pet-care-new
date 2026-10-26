package com.petcare.module.visit.service;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.module.visit.repository.PrescriptionItemRepository;

/**
 * Cài đặt {@link PrescriptionApi} (docs/adr/0076). Chạy trong transaction của sales (Order#8 thu tiền thiếu tồn):
 * {@code MANDATORY}, không {@code catch}, không audit (sales ghi, BR-BH-04). Không khóa {@code visits}: Order
 * {@code PENDING} nghĩa là lượt đã {@code COMPLETED}, và thứ tự khóa của visit là {@code visits → orders}, khóa ngược lại sẽ
 * tạo vòng. Chỉ khóa dòng đơn thuốc, bằng chính câu UPDATE. Bên gọi phải tự phân quyền và chỉ gọi cho dòng DRUG của Order
 * nguồn VISIT; dòng không phải đơn thuốc là lỗi của bên gọi.
 */
@Service
public class PrescriptionApiService implements PrescriptionApi {

    private final PrescriptionItemRepository items;
    private final Clock clock;

    public PrescriptionApiService(PrescriptionItemRepository items, Clock clock) {
        this.items = items;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExternalPurchaseAtPayment(Long orderLineId, Long actorId) {
        Objects.requireNonNull(orderLineId, "orderLineId");
        Objects.requireNonNull(actorId, "actorId");
        if (items.markExternalAtPayment(orderLineId, clock.instant()) != 1) {
            throw new IllegalStateException(
                    "Dòng Order #" + orderLineId + " không phải dòng đơn thuốc đang gắn với Order");
        }
    }
}
