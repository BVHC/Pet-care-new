package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Nơi <b>duy nhất</b> đổi {@code stock_lots.quantity} (docs/adr/0042, 04 nguyên tắc 5): mỗi lần đổi INSERT một dòng
 * {@code stock_movements} trong cùng transaction. Chỉ nhận {@link LockedLot} — lô đã khóa {@code FOR NO KEY UPDATE} và
 * số dư đọc từ chính câu khóa — nên phép kiểm "tồn không âm" (BR-KO-01) chạy trên số dư đúng. Số dư âm hoặc tràn
 * {@code int} bị từ chối trước khi ghi; CHECK {@code quantity >= 0} của DB chỉ là lưới an toàn cuối.
 */
@Component
public class StockLedger {

    private final StockLotDao lots;
    private final Clock clock;

    public StockLedger(StockLotDao lots, Clock clock) {
        this.lots = lots;
        this.clock = clock;
    }

    /**
     * @param delta khác 0; dương = nhập / hoàn, âm = xuất
     * @return lô với số dư mới (bên gọi dùng bản này nếu còn thao tác tiếp trên cùng lô)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedLot apply(LockedLot lot, int delta, MovementType type, String sourceType, long sourceId,
            long actorId) {
        if (delta == 0) {
            throw new IllegalArgumentException("Stock delta must not be zero");
        }
        int balance;
        try {
            balance = Math.addExact(lot.quantity(), delta);
        } catch (ArithmeticException overflow) {
            throw new BusinessRuleViolationException("BR-KO-01", "Số lượng của lô vượt giới hạn cho phép");
        }
        if (balance < 0) {
            throw new BusinessRuleViolationException("BR-KO-01", "Tồn của lô " + describe(lot) + " không đủ: còn "
                    + lot.quantity() + ", cần trừ " + (-delta));
        }
        Instant now = Instant.now(clock);
        lots.updateQuantity(lot.id(), balance, now);
        lots.insertMovement(lot.id(), type, delta, balance, sourceType, sourceId, actorId, now);
        return lot.withQuantity(balance);
    }

    /** Nhãn lô cho thông báo lỗi: số lô và hạn, hoặc "mặc định". */
    static String describe(LockedLot lot) {
        return lot.defaultLot() ? "mặc định #" + lot.id()
                : (lot.lotNumber() + " (hạn " + lot.expiryDate() + ")");
    }
}
