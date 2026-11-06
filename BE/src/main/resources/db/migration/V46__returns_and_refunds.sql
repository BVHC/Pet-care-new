-- =============================================================================
-- V46 — Trả hàng và hoàn tiền (T55c, docs/adr/0117; docs/08-tang3-be2-spec.md §D, UC68, UC69, ST21, BR-BH-13…15,
-- BR-TG-12…14, FSM #16, #17) + 1 tham số [CFG] returns.window_days.
--
--   * return_requests.order_id trỏ tới `orders.id` (Order bán hàng), kể cả với đơn online: dùng salesOrderId của đơn
--     online, không dùng online_orders.id (returns-v1, lưu ý ID).
--   * CHECK quyết định ↔ trạng thái (BR-BH-14, FSM #2–#5): PENDING / CANCELLED chưa có quyết định; APPROVED / REFUNDED có
--     người duyệt, thời điểm, `restock` và `refund_amount`; REJECTED có người từ chối, thời điểm và lý do
--     (`decision_note` bắt buộc), không có `restock` / `refund_amount`.
--   * refunds: đúng một trong hai nguồn (yêu cầu trả hàng hoặc đơn online hủy sau PAID, BR-TG-12). Hoàn tại quầy xong
--     phải gắn ca thu ngân (BR-TG-13). Các cột `*_at` không có trong CHECK ngoài `completed_at` ↔ SUCCEEDED.
--   * Tiền hoàn amount > 0 theo đặc tả; yêu cầu trả có refund_amount = 0 (dòng giá 0 đ) sẽ không sinh được dòng refunds:
--     để mở ở ADR-0117 (TBD-3).
-- =============================================================================

CREATE TABLE return_requests (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(20)  NOT NULL,
    order_id      BIGINT       NOT NULL REFERENCES orders (id) ON DELETE RESTRICT,
    branch_id     BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    status        VARCHAR(30)  NOT NULL,
    reason_code   VARCHAR(30)  NOT NULL,
    reason_note   VARCHAR(300),
    created_by    BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    decided_by    BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    decision_note VARCHAR(300),
    decided_at    TIMESTAMPTZ,
    restock       BOOLEAN,
    refund_amount BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_return_requests_code UNIQUE (code),
    CONSTRAINT ck_return_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_return_requests_reason_code CHECK (reason_code IN ('DEFECTIVE', 'WRONG_ITEM', 'CHANGED_MIND', 'OTHER')),
    CONSTRAINT ck_return_requests_refund_amount CHECK (refund_amount IS NULL OR refund_amount >= 0),
    CONSTRAINT ck_return_requests_decision CHECK (
        (status IN ('PENDING', 'CANCELLED')
            AND decided_by IS NULL AND decided_at IS NULL AND restock IS NULL AND refund_amount IS NULL)
        OR (status IN ('APPROVED', 'REFUNDED')
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND restock IS NOT NULL AND refund_amount IS NOT NULL)
        OR (status = 'REJECTED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_note IS NOT NULL
            AND restock IS NULL AND refund_amount IS NULL))
);
CREATE INDEX ix_return_requests_branch_status_created ON return_requests (branch_id, status, created_at);
-- Tính "đã trả / đang trả" của một Order (BR-BH-13) và kiểm FK khi xóa Order
CREATE INDEX ix_return_requests_order ON return_requests (order_id);
CREATE INDEX ix_return_requests_created_by ON return_requests (created_by);
CREATE INDEX ix_return_requests_decided_by ON return_requests (decided_by) WHERE decided_by IS NOT NULL;

CREATE TABLE return_request_lines (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id    BIGINT      NOT NULL REFERENCES return_requests (id) ON DELETE RESTRICT,
    order_line_id BIGINT      NOT NULL REFERENCES order_lines (id) ON DELETE RESTRICT,
    quantity      INT         NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_return_request_lines_request_line UNIQUE (request_id, order_line_id),
    CONSTRAINT ck_return_request_lines_quantity CHECK (quantity > 0)
);
-- Tổng số lượng đã / đang trả của một dòng Order (BR-BH-13)
CREATE INDEX ix_return_request_lines_order_line ON return_request_lines (order_line_id);

CREATE TABLE refunds (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              VARCHAR(20)  NOT NULL,
    return_request_id BIGINT       REFERENCES return_requests (id) ON DELETE RESTRICT,
    online_order_id   BIGINT       REFERENCES online_orders (id) ON DELETE RESTRICT,
    branch_id         BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    method            VARCHAR(30)  NOT NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(30)  NOT NULL,
    attempts          SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    gateway_refund_id VARCHAR(100),
    failure_reason    VARCHAR(300),
    confirmed_by      BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    cashier_shift_id  BIGINT       REFERENCES cashier_shifts (id) ON DELETE RESTRICT,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refunds_code UNIQUE (code),
    CONSTRAINT ck_refunds_method CHECK (method IN ('CASH', 'TRANSFER', 'GATEWAY')),
    CONSTRAINT ck_refunds_status CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_attempts CHECK (attempts >= 0),
    -- đúng một nguồn (BR-TG-12)
    CONSTRAINT ck_refunds_single_source CHECK ((return_request_id IS NULL) <> (online_order_id IS NULL)),
    -- hoàn tại quầy xong phải nằm trong một ca thu ngân (BR-TG-13)
    CONSTRAINT ck_refunds_counter_shift CHECK (method = 'GATEWAY' OR status <> 'SUCCEEDED' OR cashier_shift_id IS NOT NULL),
    CONSTRAINT ck_refunds_completed_at CHECK ((status = 'SUCCEEDED') = (completed_at IS NOT NULL))
);
-- ST21 quét hoàn tiền qua cổng đang chờ
CREATE INDEX ix_refunds_gateway_requested ON refunds (status, next_attempt_at)
    WHERE method = 'GATEWAY' AND status = 'REQUESTED';
CREATE INDEX ix_refunds_return_request ON refunds (return_request_id) WHERE return_request_id IS NOT NULL;
CREATE INDEX ix_refunds_online_order ON refunds (online_order_id) WHERE online_order_id IS NOT NULL;
CREATE INDEX ix_refunds_confirmed_by ON refunds (confirmed_by) WHERE confirmed_by IS NOT NULL;
-- Đối soát ca trừ hoàn tiền theo hình thức (UC72), cùng mẫu index payments (cashier_shift_id, method)
CREATE INDEX ix_refunds_cashier_shift ON refunds (cashier_shift_id, method) WHERE cashier_shift_id IS NOT NULL;

-- docs/08 §G. Khoảng min–max và đơn vị do T55c chọn (đặc tả chỉ cho giá trị mặc định): ADR-0117 mục 4.
INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('returns.window_days', '7', 'INT', '1', '30', 'ngày', 'Hạn trả hàng kể từ lúc thanh toán (đơn tại quầy) hoặc từ lúc giao (đơn online) (BR-BH-13)');
