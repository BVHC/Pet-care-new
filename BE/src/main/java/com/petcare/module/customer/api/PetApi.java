package com.petcare.module.customer.api;

import java.math.BigDecimal;

/** Owner: customer (KH) · BE-2. Caller: visit (VET khám), boarding (nhận thú). */
public interface PetApi {

    /**
     * Ghi một lần cân (BR-KH-04). Nguồn VET truyền {@code visitId}, INTAKE truyền {@code boardingBookingId},
     * OWNER để cả hai null. Trả về id weight record (BoardingCheckIn 1–1 WeightRecord).
     */
    Long recordWeight(Long petId, BigDecimal weightKg, WeightSource source,
                      Long visitId, Long boardingBookingId, Long actorId);
}
