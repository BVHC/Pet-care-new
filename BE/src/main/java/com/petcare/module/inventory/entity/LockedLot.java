package com.petcare.module.inventory.entity;

import java.time.LocalDate;

/**
 * Ảnh chụp một lô đã khóa {@code FOR NO KEY UPDATE} (docs/adr/0042): số dư đọc từ chính câu khóa nên đúng tại thời điểm
 * giữ khóa. Bất biến; mỗi lần đổi số dư {@code StockLedger} trả về bản mới. Lô không map thành entity JPA để không có
 * bản sao cũ trong persistence context.
 */
public record LockedLot(Long id, Long inventoryItemId, Long branchId, Long productId, String lotNumber,
                        LocalDate expiryDate, int quantity, boolean defaultLot) {

    /** Hết hạn khi {@code expiry_date < hôm nay}; lô hết hạn hôm nay vẫn xuất được trong ngày; lô mặc định không hết hạn. */
    public boolean expired(LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    public LockedLot withQuantity(int newQuantity) {
        return new LockedLot(id, inventoryItemId, branchId, productId, lotNumber, expiryDate, newQuantity, defaultLot);
    }
}
