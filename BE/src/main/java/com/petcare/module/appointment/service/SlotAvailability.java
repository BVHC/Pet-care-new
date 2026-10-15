package com.petcare.module.appointment.service;

import java.time.LocalTime;

/**
 * Một khung của ngày kèm quota hiệu lực và số lịch đang chiếm chỗ (BR-LH-03). {@code booked} là số thật, có thể lớn hơn
 * {@code quota} khi quota bị giảm sau khi đã có lịch ("giảm quota không ảnh hưởng lịch đã đặt").
 */
public record SlotAvailability(LocalTime start, int quota, int booked) {

    /** Hết chỗ: đủ quota; quota 0 (khung bị khóa) luôn hết chỗ. */
    public boolean full() {
        return booked >= quota;
    }
}
