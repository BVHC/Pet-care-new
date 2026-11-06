-- =============================================================================
-- V45 — Thanh toán online: giao dịch, đối soát (T55c, docs/adr/0117; docs/08-tang3-be2-spec.md §C, UC63, ST11, ST14,
-- BR-TG-06…11, FSM #15) + 2 tham số [CFG] payment.*.
--
--   * payment_transactions.code là mã tham chiếu gửi cổng (VARCHAR(40), không phải mã chứng từ 20 ký tự nên không nằm
--     trong DOCUMENT_CODE_TABLES của SchemaMigrationIT). Hai UNIQUE một phần: tối đa một giao dịch PENDING mỗi đơn
--     (BR-TG-06) và (gateway, gateway_txn_id) không trùng (idempotency của callback, BR-TG-07).
--   * Cổng KHÔNG cài ở T55: bảng chỉ lưu tên cổng (`gateway`, ví dụ VNPAY) để đổi cổng không đổi schema (BR-TG-11).
--   * Không lưu khóa bí mật, chữ ký hay tham số thô của cổng ở đây (BR-TG-07, BR-TG-11).
--   * reconciliation_items không tự sửa dữ liệu (BR-TG-10): resolved_* chỉ ghi chú của SUPER_MANAGER.
-- =============================================================================

CREATE TABLE payment_transactions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(40)   NOT NULL,
    online_order_id BIGINT        NOT NULL REFERENCES online_orders (id) ON DELETE RESTRICT,
    gateway         VARCHAR(20)   NOT NULL,
    amount          BIGINT        NOT NULL,
    status          VARCHAR(30)   NOT NULL,
    gateway_txn_id  VARCHAR(100),
    redirect_url    VARCHAR(1000),
    expires_at      TIMESTAMPTZ   NOT NULL,
    paid_at         TIMESTAMPTZ,
    failure_reason  VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_transactions_code UNIQUE (code),
    CONSTRAINT ck_payment_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_transactions_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    -- paid_at chỉ có ở giao dịch thành công (Giao dịch#2)
    CONSTRAINT ck_payment_transactions_paid_at CHECK ((status = 'SUCCEEDED') = (paid_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_payment_transactions_order_pending ON payment_transactions (online_order_id)
    WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_payment_transactions_gateway_txn ON payment_transactions (gateway, gateway_txn_id)
    WHERE gateway_txn_id IS NOT NULL;
-- Kiểm FK khi xóa đơn online và tra mọi giao dịch của một đơn (trang kết quả, ST11)
CREATE INDEX ix_payment_transactions_order ON payment_transactions (online_order_id);
-- ST11 quét giao dịch PENDING quá hạn
CREATE INDEX ix_payment_transactions_pending_expires ON payment_transactions (status, expires_at)
    WHERE status = 'PENDING';

CREATE TABLE payment_reconciliations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recon_date     DATE        NOT NULL,
    status         VARCHAR(30) NOT NULL,
    txn_count      INT         NOT NULL DEFAULT 0,
    mismatch_count INT         NOT NULL DEFAULT 0,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_reconciliations_recon_date UNIQUE (recon_date),
    CONSTRAINT ck_payment_reconciliations_status CHECK (status IN ('RUNNING', 'MATCHED', 'MISMATCHED', 'FAILED')),
    CONSTRAINT ck_payment_reconciliations_counts CHECK (txn_count >= 0 AND mismatch_count >= 0)
);

CREATE TABLE reconciliation_items (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reconciliation_id BIGINT       NOT NULL REFERENCES payment_reconciliations (id) ON DELETE RESTRICT,
    kind              VARCHAR(30)  NOT NULL,
    transaction_id    BIGINT       REFERENCES payment_transactions (id) ON DELETE RESTRICT,
    gateway_ref       VARCHAR(100),
    our_amount        BIGINT,
    gateway_amount    BIGINT,
    resolved_by       BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    resolved_note     VARCHAR(300),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_reconciliation_items_kind CHECK (kind IN ('MISSING_LOCAL', 'MISSING_GATEWAY', 'AMOUNT_DIFF', 'STATUS_DIFF'))
);
CREATE INDEX ix_reconciliation_items_reconciliation ON reconciliation_items (reconciliation_id);
CREATE INDEX ix_reconciliation_items_transaction ON reconciliation_items (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX ix_reconciliation_items_resolved_by ON reconciliation_items (resolved_by) WHERE resolved_by IS NOT NULL;

-- docs/08 §G. Khoảng min–max và đơn vị do T55c chọn (đặc tả chỉ cho giá trị mặc định): ADR-0117 mục 4.
INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('payment.txn_ttl_minutes',     '15', 'INT', '5', '60', 'phút', 'Thời hạn một giao dịch thanh toán online ở trạng thái PENDING; quá hạn thì ST11 hỏi cổng rồi chuyển EXPIRED (BR-TG-09)'),
('payment.refund_max_attempts', '3',  'INT', '1', '10', 'lần',  'Số lần ST21 thử hoàn tiền qua cổng khi gặp lỗi tạm thời, cách nhau 15 phút; hết lần thì hoàn tiền sang FAILED (BR-TG-14)');
