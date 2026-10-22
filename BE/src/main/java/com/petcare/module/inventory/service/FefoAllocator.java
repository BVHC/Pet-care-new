package com.petcare.module.inventory.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.petcare.module.inventory.entity.LockedLot;

/**
 * Chia số lượng xuất theo FEFO (BR-KO-05): lô hết hạn trước, xuất trước; <b>không bao giờ xuất lô đã hết hạn</b>
 * ({@code expiry_date < hôm nay}; lô hết hạn hôm nay còn dùng được). Hàm thuần trên các lô đã khóa — bên gọi giữ khóa nên
 * số dư là số dư thật. Thứ tự: hạn tăng dần, lô mặc định (không hạn) sau cùng, cùng hạn thì id nhỏ trước.
 */
final class FefoAllocator {

    /** Lấy {@code quantity} từ {@code lot}. */
    record Take(LockedLot lot, int quantity) {
    }

    private static final Comparator<LockedLot> FEFO = Comparator
            .comparing(LockedLot::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(LockedLot::id);

    private FefoAllocator() {
    }

    /** Lô dùng được (còn số dư, chưa hết hạn) theo thứ tự FEFO. */
    static List<LockedLot> usable(List<LockedLot> lots, LocalDate today) {
        return lots.stream().filter(l -> l.quantity() > 0 && !l.expired(today)).sorted(FEFO).toList();
    }

    /** Tổng tồn khả dụng của các lô (BR-KO-01). */
    static int available(List<LockedLot> lots, LocalDate today) {
        long total = usable(lots, today).stream().mapToLong(LockedLot::quantity).sum();
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * @return các lần lấy, theo thứ tự FEFO, tổng đúng {@code quantity}; rỗng nếu tồn khả dụng không đủ (không chia
     *         một phần)
     */
    static Optional<List<Take>> allocate(List<LockedLot> lots, int quantity, LocalDate today) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        List<Take> takes = new ArrayList<>();
        int remaining = quantity;
        for (LockedLot lot : usable(lots, today)) {
            int take = Math.min(lot.quantity(), remaining);
            takes.add(new Take(lot, take));
            remaining -= take;
            if (remaining == 0) {
                return Optional.of(takes);
            }
        }
        return Optional.empty();
    }

    /** Lô FEFO đầu tiên còn đủ {@code quantity} trong một lô (dùng cho mũi tiêm, mỗi mũi từ đúng một lô). */
    static Optional<LockedLot> firstLotWith(List<LockedLot> lots, int quantity, LocalDate today) {
        return usable(lots, today).stream().filter(l -> l.quantity() >= quantity).findFirst();
    }
}
