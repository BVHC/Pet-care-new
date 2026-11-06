-- =============================================================================
-- V44 — Thương mại điện tử: giỏ hàng, đơn online, giữ hàng (T55c, docs/adr/0117; docs/08-tang3-be2-spec.md §B,
-- UC61, 62, 64, 65, ST09, ST10, ST12, FSM #14) + 3 tham số [CFG] shop.*.
--
--   * online_orders.order_id trỏ tới Order nguồn ONLINE (V42 của T55b, ADR-0116, mở giá trị `ONLINE` và nới ba CHECK còn lại của orders). Địa chỉ giao là
--     SNAPSHOT (receiver_name, receiver_phone, ship_address), không FK tới addresses (BR-BH-08, addresses xóa cứng được).
--   * CHECK thời điểm ↔ trạng thái theo khuôn care_tasks (V22, docs/adr/0081): cột `*_at` chỉ có giá trị khi đơn đã đi qua
--     trạng thái đó. CANCELLED được phép có hoặc không có paid_at / shipped_at (hủy lúc chờ thanh toán, hoặc sau giao thất
--     bại: FSM #3, #4, #10, #13); delivered_at / completed_at thì không bao giờ có ở CANCELLED.
--   * Lý do giao thất bại (FSM #8 "Có lý do") KHÔNG có cột trong đặc tả 08 §B: để mở ở ADR-0117 (TBD-2), V49 còn trống để
--     thêm cột nếu Dũng chốt lưu ở đây thay vì audit.
--   * Thứ tự cột (status, payment_due_at) khớp tác vụ ST10, (delivered_at) khớp ST12; các index một phần giữ literal
--     trạng thái để truy vấn của job khớp được (CLAUDE.md, Native SQL).
-- =============================================================================

CREATE TABLE carts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_carts_customer_id UNIQUE (customer_id)
);

CREATE TABLE cart_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id    BIGINT      NOT NULL REFERENCES carts (id) ON DELETE RESTRICT,
    product_id BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity   SMALLINT    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_items_cart_product UNIQUE (cart_id, product_id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 99)
);
CREATE INDEX ix_cart_items_product ON cart_items (product_id);

CREATE TABLE online_orders (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL,
    order_id         BIGINT       NOT NULL REFERENCES orders (id) ON DELETE RESTRICT,
    customer_id      BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    branch_id        BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    status           VARCHAR(30)  NOT NULL,
    receiver_name    VARCHAR(100) NOT NULL,
    receiver_phone   VARCHAR(15)  NOT NULL,
    ship_address     VARCHAR(300) NOT NULL,
    shipping_fee     BIGINT       NOT NULL,
    carrier          VARCHAR(100),
    tracking_code    VARCHAR(100),
    redelivery_count SMALLINT     NOT NULL DEFAULT 0,
    payment_due_at   TIMESTAMPTZ  NOT NULL,
    paid_at          TIMESTAMPTZ,
    shipped_at       TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(300),
    cancelled_by     BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_online_orders_code UNIQUE (code),
    CONSTRAINT uq_online_orders_order_id UNIQUE (order_id),
    CONSTRAINT ck_online_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED',
                                                         'DELIVERY_FAILED', 'DELIVERED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_online_orders_shipping_fee CHECK (shipping_fee >= 0),
    CONSTRAINT ck_online_orders_redelivery_count CHECK (redelivery_count >= 0),
    CONSTRAINT ck_online_orders_paid_at CHECK (
        (status IN ('PAID', 'PROCESSING', 'SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'COMPLETED') AND paid_at IS NOT NULL)
        OR (status = 'PENDING_PAYMENT' AND paid_at IS NULL)
        OR status = 'CANCELLED'),
    CONSTRAINT ck_online_orders_shipped_at CHECK (
        (status IN ('SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'COMPLETED') AND shipped_at IS NOT NULL)
        OR (status IN ('PENDING_PAYMENT', 'PAID', 'PROCESSING') AND shipped_at IS NULL)
        OR status = 'CANCELLED'),
    CONSTRAINT ck_online_orders_delivered_at CHECK ((status IN ('DELIVERED', 'COMPLETED')) = (delivered_at IS NOT NULL)),
    CONSTRAINT ck_online_orders_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_online_orders_cancelled_at CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_online_orders_cancel_reason CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL)),
    CONSTRAINT ck_online_orders_cancelled_by CHECK (cancelled_by IS NULL OR status = 'CANCELLED'),
    -- SHIPPED có đơn vị vận chuyển và mã vận đơn (BR-BH-11); giữ nguyên sau giao thất bại / đã giao
    CONSTRAINT ck_online_orders_carrier CHECK (shipped_at IS NULL OR (carrier IS NOT NULL AND tracking_code IS NOT NULL))
);
CREATE INDEX ix_online_orders_branch_status_due ON online_orders (branch_id, status, payment_due_at);
CREATE INDEX ix_online_orders_customer_created ON online_orders (customer_id, created_at DESC);
CREATE INDEX ix_online_orders_pending_payment_due ON online_orders (status, payment_due_at)
    WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_online_orders_delivered_at ON online_orders (delivered_at) WHERE status = 'DELIVERED';
CREATE INDEX ix_online_orders_cancelled_by ON online_orders (cancelled_by) WHERE cancelled_by IS NOT NULL;

CREATE TABLE stock_reservations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    online_order_id BIGINT      NOT NULL REFERENCES online_orders (id) ON DELETE RESTRICT,
    branch_id       BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    product_id      BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity        INT         NOT NULL,
    status          VARCHAR(30) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_reservations_order_product UNIQUE (online_order_id, product_id),
    CONSTRAINT ck_stock_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED'))
);
-- Tồn khả dụng = tồn trừ phần đang giữ (BR-BH-09): tổng quantity của HELD theo (chi nhánh, sản phẩm)
CREATE INDEX ix_stock_reservations_held ON stock_reservations (branch_id, product_id) WHERE status = 'HELD';

-- docs/08 §G. Khoảng min–max và đơn vị do T55c chọn (đặc tả chỉ cho giá trị mặc định): ADR-0117 mục 4.
INSERT INTO system_configs (key, value, value_type, min_value, max_value, unit, description) VALUES
('shop.payment_window_hours', '24', 'INT', '1', '168', 'giờ',  'Hạn thanh toán đơn online; quá hạn thì ST10 hủy đơn và nhả hàng giữ (BR-BH-12)'),
('shop.auto_confirm_days',    '7',  'INT', '1', '30',  'ngày', 'Số ngày sau khi giao thì ST12 tự xác nhận đã nhận nếu không có yêu cầu trả hàng đang mở (BR-BH-12)'),
('shop.max_redelivery',       '2',  'INT', '0', '5',   'lần',  'Số lần giao lại tối đa sau giao thất bại (BR-BH-11)');
