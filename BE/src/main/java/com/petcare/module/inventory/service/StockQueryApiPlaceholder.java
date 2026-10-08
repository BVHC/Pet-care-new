package com.petcare.module.inventory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.inventory.api.StockQueryApi;

/**
 * Giữ chỗ cho {@link StockQueryApi} tới khi module inventory (BE-2) cài thật. Theo 06-module-contracts §1: mọi
 * method ném lỗi, không trả giá trị "an toàn". Hệ quả: tắt cờ quản lý hạn dùng của sản phẩm (BR-SP-05) trả 500.
 * BE-2 xóa lớp này (cùng {@code StockQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class StockQueryApiPlaceholder implements StockQueryApi {

    static final String MESSAGE = "StockQueryApi chưa được cài (module inventory, BE-2)";

    @Override
    public int availableQuantity(Long branchId, Long productId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<Long> branchIdsWithAvailableStock(Long productId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean hasStockAnywhere(Long productId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
