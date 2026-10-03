package com.petcare.module.customer.api;

/**
 * Publisher: customer (BR-KH-08, UC26) · BE-2. Phát đồng bộ trong transaction chuyển chủ.
 * Listener: appointment (Lịch hẹn#5), boarding (Đặt chỗ#5), care (Care Task OPEN của thú chuyển sang
 * chủ mới — quyết định Q1 trong 06-module-contracts).
 */
public record PetOwnerTransferredEvent(Long petId, Long fromCustomerId, Long toCustomerId, Long actorId) {}
