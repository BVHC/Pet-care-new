package com.petcare.module.catalog.service;

/** Mã action audit của module catalog (convention 08 §8.3: SP chỉ ghi audit đổi giá, UC29, UC30). */
final class CatalogAuditActions {

    static final String PRICE_CHANGED = "PRICE_CHANGED";

    private CatalogAuditActions() {
    }
}
