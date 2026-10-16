-- T18 (kho, ADR-0042, ADR-0043).
-- 1) Bang dem ma chung tu: day so lien tuc tuyet doi theo loai x thang (BR-KO-03). Khong co trong ERD v16
--    (erd 13 muc 18); platform/sequence/DocumentCodeGenerator la noi duy nhat ghi bang nay.
CREATE TABLE document_counters (
    doc_type   VARCHAR(30) NOT NULL,
    period     VARCHAR(4)  NOT NULL,
    last_value INT         NOT NULL,
    CONSTRAINT pk_document_counters PRIMARY KEY (doc_type, period),
    CONSTRAINT ck_document_counters_last_value CHECK (last_value > 0)
);

-- 2) Index cho truy van danh sach va kiem FK cua module kho (V1 chi co index FEFO, movement, unique).
CREATE INDEX ix_inventory_items_product_id ON inventory_items (product_id);
CREATE INDEX ix_stock_receipts_branch_date ON stock_receipts (branch_id, receipt_date DESC, id DESC);
CREATE INDEX ix_stock_receipts_supplier_id ON stock_receipts (supplier_id);
CREATE INDEX ix_stock_receipt_lines_receipt_id ON stock_receipt_lines (stock_receipt_id);
CREATE INDEX ix_stock_receipt_lines_product_id ON stock_receipt_lines (product_id);
CREATE INDEX ix_stock_receipt_lines_lot_id ON stock_receipt_lines (stock_lot_id) WHERE stock_lot_id IS NOT NULL;
CREATE INDEX ix_stock_adjustments_branch_created_at ON stock_adjustments (branch_id, created_at DESC, id DESC);
CREATE INDEX ix_stock_adjustment_lines_adjustment_id ON stock_adjustment_lines (stock_adjustment_id);
CREATE INDEX ix_stock_adjustment_lines_lot_id ON stock_adjustment_lines (stock_lot_id);
