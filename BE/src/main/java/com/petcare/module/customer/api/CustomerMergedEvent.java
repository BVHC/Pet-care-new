package com.petcare.module.customer.api;

/**
 * Publisher: customer · gộp hồ sơ khách trùng (UC27, BR-KH-12; docs/06 §3, §6b hàng 14) · BE-2. Phát đồng bộ trong
 * transaction gộp, chỉ khai báo ở T55c (docs/adr/0117): <b>chưa có bên phát</b>, bản thật trước 7/12 (T75).
 * Listener (mỗi module giữ {@code customer_id} tự đổi {@code sourceId} → {@code targetId} của bản ghi mình, dùng
 * {@code @EventListener} đồng bộ như {@link PetOwnerTransferredEvent}): appointment, visit, boarding, sales (orders, carts,
 * online orders), care, content (feedback), complaint.
 */
public record CustomerMergedEvent(Long sourceId, Long targetId) {}
