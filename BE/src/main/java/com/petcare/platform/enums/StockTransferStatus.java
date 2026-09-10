package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 11 · docs/06-erd.md §4 stock_transfer_status_enum */
public enum StockTransferStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    IN_TRANSIT,
    DISCREPANCY_RECORDED,
    RECEIVED
}
