package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.inventory.repository.InventoryQueryRepository;

import lombok.RequiredArgsConstructor;

/**
 * Báo cáo tồn dưới ngưỡng và lô sắp / đã hết hạn (BR-KO-07, BR-BC-03 (5); docs/adr/0087); ST08 dùng chung. Chỉ đọc, không
 * khóa, tham gia transaction của bên gọi. "Hôm nay" theo {@link Clock} (giờ Việt Nam).
 */
@Service
@RequiredArgsConstructor
public class StockReportService implements StockReportApi {

    private final InventoryQueryRepository inventory;
    private final Clock clock;

    @Override
    public List<LowStockRow> lowStock(Long branchId) {
        return inventory.lowStock(branchId, LocalDate.now(clock));
    }

    @Override
    public List<ExpiringLotRow> expiringLots(Long branchId, int withinDays) {
        return inventory.expiringLots(branchId, LocalDate.now(clock).plusDays(withinDays));
    }
}
