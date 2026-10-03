package com.petcare.module.customer.api;

import java.time.LocalDate;

/**
 * Publisher: customer (BR-KH-05, UC24) · BE-2. Phát đồng bộ trong transaction đánh dấu đã mất.
 * Listener: appointment (Lịch hẹn#5), boarding (Đặt chỗ#5), care (Care Task#4 / ST19), visit (hủy nhắc tái khám).
 */
public record PetDeceasedEvent(Long petId, Long customerId, LocalDate deceasedOn, Long actorId) {}
