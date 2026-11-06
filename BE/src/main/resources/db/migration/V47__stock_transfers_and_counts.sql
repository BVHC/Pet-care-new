-- =============================================================================
-- V47 — Kho mở rộng: chuyển kho, kiểm kê, lý do điều chỉnh mới (T55c, docs/adr/0117; docs/08-tang3-be2-spec.md §E,
-- UC77, UC78, UC79, BR-KO-08…13, FSM #18, #19; §D BR-BH-14).
--
--   * stock_transfers / stock_counts dùng mã chứng từ (CK-…, KK-…) sinh bằng platform.sequence.DocumentCodeGenerator
--     (bảng document_counters, docs/adr/0043): không cần migration cho tiền tố mới; đặc tả ghi nhầm tên bảng là
--     `document_code_sequences` (ADR-0117, mục khác biệt).
--   * stock_counts.scope là VARCHAR(30) (đặc tả ghi 20): mọi cột enum có CHECK phải là VARCHAR(30) (erd §0,
--     SchemaMigrationIT.enumCheckColumnsAreVarchar30).
--   * Sửa ba CHECK của V1 bằng DROP + ADD cùng tên (không sửa V1):
--       - ck_stock_movements_movement_type  + TRANSFER_OUT, TRANSFER_IN, TRANSFER_CANCEL (05 §13 mục 26)
--       - ck_stock_movements_source_type    + `stock_transfers`. Đặc tả không liệt kê nhưng movement của phiếu chuyển
--         cần nguồn (BR-KO-09 "ghi stock_movements hai phía"): giả định A-4 của ADR-0117.
--       - ck_stock_adjustments_reason       + COUNT_DIFF (kiểm kê), RETURN_DISCARD (loại bỏ hàng trả). COUNT_MISMATCH của
--         V1 GIỮ NGUYÊN: dòng cũ vẫn hợp lệ; hai giá trị gần nghĩa nhau, ADR-0117 ghi để T74 chọn dùng cái nào.
--   * Loại biến động cho việc hoàn hàng về lô gốc (đơn online hủy, trả hàng) KHÔNG có trong đặc tả: chưa thêm giá trị
--     nào (TBD-1 của ADR-0117); V49 còn trống để thêm khi T63 / T73 chốt.
-- =============================================================================

CREATE TABLE stock_transfers (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL,
    source_branch_id BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    dest_branch_id   BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    status           VARCHAR(30)  NOT NULL,
    created_by       BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    received_by      BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    received_at      TIMESTAMPTZ,
    cancelled_by     BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    cancel_reason    VARCHAR(300),
    cancelled_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_transfers_code UNIQUE (code),
    CONSTRAINT ck_stock_transfers_status CHECK (status IN ('IN_TRANSIT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_stock_transfers_branches CHECK (source_branch_id <> dest_branch_id),
    -- Nhận hàng: người nhận và lúc nhận đi cùng nhau, chỉ có ở RECEIVED (BR-KO-09)
    CONSTRAINT ck_stock_transfers_received CHECK (
        (status = 'RECEIVED' AND received_by IS NOT NULL AND received_at IS NOT NULL)
        OR (status <> 'RECEIVED' AND received_by IS NULL AND received_at IS NULL)),
    -- Hủy: người hủy, lúc hủy và lý do (bắt buộc, BR-KO-10) đi cùng nhau, chỉ có ở CANCELLED
    CONSTRAINT ck_stock_transfers_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_by IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL))
);
CREATE INDEX ix_stock_transfers_source_status ON stock_transfers (source_branch_id, status);
CREATE INDEX ix_stock_transfers_dest_status ON stock_transfers (dest_branch_id, status);
CREATE INDEX ix_stock_transfers_created_by ON stock_transfers (created_by);
CREATE INDEX ix_stock_transfers_received_by ON stock_transfers (received_by) WHERE received_by IS NOT NULL;
CREATE INDEX ix_stock_transfers_cancelled_by ON stock_transfers (cancelled_by) WHERE cancelled_by IS NOT NULL;

CREATE TABLE stock_transfer_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id   BIGINT      NOT NULL REFERENCES stock_transfers (id) ON DELETE RESTRICT,
    product_id    BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    source_lot_id BIGINT      NOT NULL REFERENCES stock_lots (id) ON DELETE RESTRICT,
    quantity      INT         NOT NULL,
    lot_number    VARCHAR(50),
    expiry_date   DATE,
    dest_lot_id   BIGINT      REFERENCES stock_lots (id) ON DELETE RESTRICT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_transfer_lines_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_stock_transfer_lines_transfer ON stock_transfer_lines (transfer_id);
-- Truy vết lô (BR-KO-13): phiếu chuyển nào đã lấy / tạo lô này
CREATE INDEX ix_stock_transfer_lines_source_lot ON stock_transfer_lines (source_lot_id);
CREATE INDEX ix_stock_transfer_lines_dest_lot ON stock_transfer_lines (dest_lot_id) WHERE dest_lot_id IS NOT NULL;

CREATE TABLE stock_counts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(20)  NOT NULL,
    branch_id     BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    status        VARCHAR(30)  NOT NULL,
    scope         VARCHAR(30)  NOT NULL,
    category_id   BIGINT       REFERENCES product_categories (id) ON DELETE RESTRICT,
    created_by    BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    confirmed_by  BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    confirmed_at  TIMESTAMPTZ,
    cancel_reason VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_counts_code UNIQUE (code),
    CONSTRAINT ck_stock_counts_status CHECK (status IN ('OPEN', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_stock_counts_scope CHECK (scope IN ('ALL', 'CATEGORY')),
    -- Phạm vi theo danh mục thì phải có danh mục (BR-KO-12)
    CONSTRAINT ck_stock_counts_category CHECK ((scope = 'CATEGORY') = (category_id IS NOT NULL)),
    CONSTRAINT ck_stock_counts_confirmed CHECK (
        (status = 'CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
        OR (status <> 'CONFIRMED' AND confirmed_by IS NULL AND confirmed_at IS NULL)),
    -- Hủy phiên phải có lý do (FSM #19 dòng 3); phiên khác không có lý do hủy
    CONSTRAINT ck_stock_counts_cancel_reason CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);
-- Tối đa một phiên OPEN mỗi chi nhánh (BR-KO-12)
CREATE UNIQUE INDEX uq_stock_counts_branch_open ON stock_counts (branch_id) WHERE status = 'OPEN';
CREATE INDEX ix_stock_counts_branch_status ON stock_counts (branch_id, status);
CREATE INDEX ix_stock_counts_created_by ON stock_counts (created_by);
CREATE INDEX ix_stock_counts_confirmed_by ON stock_counts (confirmed_by) WHERE confirmed_by IS NOT NULL;
CREATE INDEX ix_stock_counts_category ON stock_counts (category_id) WHERE category_id IS NOT NULL;

CREATE TABLE stock_count_lines (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    count_id             BIGINT      NOT NULL REFERENCES stock_counts (id) ON DELETE RESTRICT,
    stock_lot_id         BIGINT      NOT NULL REFERENCES stock_lots (id) ON DELETE RESTRICT,
    counted_qty          INT         NOT NULL,
    system_qty_at_confirm INT,
    adjustment_id        BIGINT      REFERENCES stock_adjustments (id) ON DELETE RESTRICT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_count_lines_count_lot UNIQUE (count_id, stock_lot_id),
    CONSTRAINT ck_stock_count_lines_counted CHECK (counted_qty >= 0),
    CONSTRAINT ck_stock_count_lines_system_qty CHECK (system_qty_at_confirm IS NULL OR system_qty_at_confirm >= 0)
);
CREATE INDEX ix_stock_count_lines_stock_lot ON stock_count_lines (stock_lot_id);
CREATE INDEX ix_stock_count_lines_adjustment ON stock_count_lines (adjustment_id) WHERE adjustment_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Giá trị mới của ba CHECK trong kho (05 §13 mục 26). DROP + ADD cùng tên.
-- ---------------------------------------------------------------------------
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movements_movement_type;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_movement_type CHECK (movement_type IN (
    'RECEIPT', 'RECEIPT_CANCEL', 'SALE', 'VACCINATION', 'VACCINATION_REVERT', 'ADJUSTMENT',
    'TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_CANCEL'));

ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movements_source_type;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_source_type CHECK (source_type IN (
    'stock_receipts', 'orders', 'vaccinations', 'stock_adjustments', 'stock_transfers'));

ALTER TABLE stock_adjustments DROP CONSTRAINT ck_stock_adjustments_reason;
ALTER TABLE stock_adjustments ADD CONSTRAINT ck_stock_adjustments_reason CHECK (reason IN (
    'DAMAGED', 'EXPIRED', 'COUNT_MISMATCH', 'OTHER', 'COUNT_DIFF', 'RETURN_DISCARD'));
