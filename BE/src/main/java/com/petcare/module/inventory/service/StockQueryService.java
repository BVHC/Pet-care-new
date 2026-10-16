package com.petcare.module.inventory.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.inventory.repository.InventoryQueryRepository;

/**
 * {@link StockQueryApi} thật (thay {@code StockQueryApiPlaceholder}, docs/adr/0042). Chỉ đọc, không khóa: bên quyết định
 * trừ kho dùng {@code StockApi} để khóa và kiểm lại. Tồn khả dụng = tổng lô chưa hết hạn (BR-KO-01);
 * {@code hasStockAnywhere} tính cả lô đã hết hạn còn số dư (BR-SP-05).
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService implements StockQueryApi {

    private final InventoryQueryRepository inventory;
    private final Clock clock;

    public StockQueryService(InventoryQueryRepository inventory, Clock clock) {
        this.inventory = inventory;
        this.clock = clock;
    }

    @Override
    public int availableQuantity(Long branchId, Long productId) {
        return inventory.availableQuantity(branchId, productId, LocalDate.now(clock));
    }

    @Override
    public List<Long> branchIdsWithAvailableStock(Long productId) {
        return inventory.branchIdsWithAvailableStock(productId, LocalDate.now(clock));
    }

    @Override
    public boolean hasStockAnywhere(Long productId) {
        return inventory.hasStockAnywhere(productId);
    }
}
