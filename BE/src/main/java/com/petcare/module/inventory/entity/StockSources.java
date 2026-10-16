package com.petcare.module.inventory.entity;

/** Giá trị {@code stock_movements.source_type} (erd §9): tên bảng chứng từ nguồn, không có FK vì đa hình. */
public final class StockSources {

    public static final String STOCK_RECEIPTS = "stock_receipts";
    public static final String ORDERS = "orders";
    public static final String VACCINATIONS = "vaccinations";
    public static final String STOCK_ADJUSTMENTS = "stock_adjustments";

    private StockSources() {
    }
}
